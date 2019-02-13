import SharePriceGetter.StockDataResponse
import TrainerChildActor.{GetPortfolio, Train}
import TrainerRouterActor._
import akka.actor.SupervisorStrategy.{Escalate, Restart, Resume, Stop}
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Stash, Terminated}
import akka.pattern.{Backoff, BackoffSupervisor, ask, pipe}
import akka.routing._
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object TrainerRouterActor {
  sealed trait Trainer
  case class SendTrainingData(stockData: StockDataResponse) extends Trainer
  case object StartTraining extends Trainer
  case object GetAvg extends Trainer
  case object GetStd extends Trainer
  case object IsEverythingDone extends Trainer

  sealed trait TrainerState
  case object Ready extends TrainerState
  case object NoTrainingDataReceived extends TrainerState
  case object Trained extends TrainerState
  case object TrainingNotCompleted extends TrainerState
  case object Completed extends TrainerState

  sealed trait TrainerData
  case object NotComputed extends TrainerData with TrainerState
  case class TrainedData(portfolio: Double) extends TrainerData

  case class Died(ref: ActorRef, router: Router)

  val noOfChildren = 10
  def props(policyActor: ActorRef, budget: Double, noOfStocks: Int) = Props(new TrainerRouterActor(policyActor, budget, noOfStocks))
}

class TrainerRouterActor(policyActor: ActorRef, budget: Double, noOfStocks: Int) extends Actor with ActorLogging with Stash {
  implicit val ec: ExecutionContext = context.dispatcher
  implicit val timeout: Timeout = Timeout(10 seconds)

  lazy val childTrainerProp: Props = Props(new TrainerChildActor(policyActor, budget, noOfStocks)) //this is for test

  private lazy val childProps: Props = BackoffSupervisor.props(Backoff.onFailure(
    childTrainerProp,
    "child-trainer",
    3 seconds,
    1 minute,
    0.2
  ).withSupervisorStrategy(
    OneForOneStrategy() {
      case _: ArithmeticException      => Resume
      case _: NullPointerException     => Restart
      case _: IllegalArgumentException => Stop
      case _: Exception                => Escalate
    }))

  private lazy val children = for(i <- 0 until noOfChildren) yield {
    val child = context.actorOf(childProps, s"child_trainer_supervisor_wrapped$i")
    context.watch(child)
    ActorRefRoutee(child)
  }

  override def receive: Receive = awaitingTrainingData(Router(BroadcastRoutingLogic(), children))

  private def awaitingTrainingData(router: Router): Receive = getRoutees(router) orElse {
    case SendTrainingData(stockData) =>
      log.info("training data received")
      unstashAll()
      context.become(trainingDataPresent(stockData, router))
    case GetStd | GetAvg | IsEverythingDone =>
      sender() ! NoTrainingDataReceived
    case GetRoutees =>
      sender() ! router.routees
    case StartTraining =>
      stash()
    case unknown =>
      log.info(s"unknown : $unknown message received")
      stash()
  }

  private def commonForPrePostTraining(trainingDataInput: StockDataResponse, actors: Option[Seq[ActorRef]], router: Router): Receive =
    getRoutees(router) orElse {
    case StartTraining =>
      log.info("training start")
      router.route(Train(trainingDataInput), sender())
    case GetStd =>
      actors.foldLeft[Future[_]](Future{NotComputed})(
        (_, actorSeq) => computePortfolios(actorSeq).map(stdDev[Double])) pipeTo sender()
    case GetAvg =>
      actors.foldLeft[Future[_]](Future{NotComputed})(
        (_, actorSeq) => computePortfolios(actorSeq).map(portfolios => portfolios.sum / portfolios.size)) pipeTo sender()
  }

  private def trainingDataPresent(trainingDataInput: StockDataResponse, router: Router): Receive =
    commonForPrePostTraining(trainingDataInput, None, router) orElse {
    case GetStd | GetAvg | IsEverythingDone =>
      sender() ! NotComputed
    case Terminated(ref) =>
      context.become(trainingDataPresent(trainingDataInput, createNewChildWhenTerminated(ref, router)))
    case Trained =>
      unstashAll()
      context.become(trained(trainingDataInput, Seq(sender()), router.removeRoutee(sender())))
    case _ =>
      stash()
  }

  private def trained(trainingDataInput: StockDataResponse, actors: Seq[ActorRef], router: Router): Receive =
    commonForPrePostTraining(trainingDataInput, Some(actors), router) orElse {
    case Trained =>
      val trainedActors = actors :+ sender(); val cleanedRouter = router.removeRoutee(sender())
      if(trainedActors.size == noOfChildren) context.become(completed(trainingDataInput, trainedActors, cleanedRouter))
      else context.become(trained(trainingDataInput, trainedActors, cleanedRouter))
    case Terminated(ref) =>
      self ! Died(ref, createNewChildWhenTerminated(ref, router))
      self ! StartTraining
    case Died(ref, newRouter) if actors.contains(ref) =>
      context.become(trained(trainingDataInput, actors.diff(Seq(ref)), newRouter))
    case IsEverythingDone =>
      sender() ! TrainingNotCompleted
  }

  private def completed(trainingDataInput: StockDataResponse, actors: Seq[ActorRef], router: Router): Receive =
    getRoutees(router) orElse commonForPrePostTraining(trainingDataInput, Some(actors), router) orElse {
    case IsEverythingDone =>
      log.info("Completed")
      sender() ! Completed
  }

  private def getRoutees(router: Router): Receive = {
    case GetRoutees =>
      sender() ! router.routees
  }

  private def computePortfolios(actors: Seq[ActorRef]): Future[Seq[Double]] = Future.sequence(actors
        .map(_ ? GetPortfolio)
        .map(_.mapTo[TrainerData])).map(_.flatMap{case TrainedData(p) => Some(p); case _ => None})

  private def createNewChildWhenTerminated(ref: ActorRef, router: Router): Router = {
    val routerCleaned = router.removeRoutee(ref)
    val newChild = context.actorOf(childProps)
    context.watch(newChild)
    routerCleaned.addRoutee(newChild)
  }

  import Numeric.Implicits._ //The following three functions are copied from https://stackoverflow.com/questions/39617213/scala-what-is-the-generic-way-to-calculate-standard-deviation/44878370
  def mean[T: Numeric](xs: Iterable[T]): Double = xs.sum.toDouble / xs.size
  def variance[T: Numeric](xs: Iterable[T]): Double = xs.map(_.toDouble).map(a => math.pow(a - mean(xs), 2)).sum / xs.size
  def stdDev[T: Numeric](xs: Iterable[T]): Double = math.sqrt(variance(xs))
}