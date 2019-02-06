import SharePriceGetter.StockDataResponse
import ShareTradeHelper.system
import TrainerChildActor.{GetPortfolio, Train}
import TrainerRouterActor._
import akka.actor.SupervisorStrategy.{Escalate, Restart, Resume, Stop}
import akka.actor.{Actor, ActorRef, OneForOneStrategy, Props, Stash, Terminated}
import akka.pattern.{Backoff, BackoffSupervisor, ask, pipe}
import akka.routing.{ActorRefRoutee, Router, SmallestMailboxRoutingLogic}
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object TrainerRouterActor {
  sealed trait Trainer
  case class SendTrainingData(stockData: StockDataResponse) extends Trainer
  case object StartTraining extends Trainer
  case object GetAvg extends Trainer
  case object GetStd extends Trainer
  case object IsEverythingDone extends Trainer

  sealed trait TrainerState
  case object Ready extends TrainerState
  case object Trained extends TrainerState
  case object NotCompleted extends TrainerState
  case object Completed extends TrainerState

  sealed trait TrainerData
  case object NoTrainingDataReceived extends TrainerData
  case object NotComputed extends TrainerData
  case class TrainingData(portfolio: Double) extends TrainerData

  case class Died(ref: ActorRef)

  val noOfChildren = 10
  def props(policyActor: ActorRef, budget: Double, noOfStocks: Int) = Props(new TrainerRouterActor(policyActor, budget, noOfStocks))
}

class TrainerRouterActor(policyActor: ActorRef, budget: Double, noOfStocks: Int) extends Actor with Stash {
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(10 seconds)

  val childTrainer: TrainerChildActor = new TrainerChildActor(policyActor, budget, noOfStocks) //this is for test

  private def childProps: Props = BackoffSupervisor.props(Backoff.onFailure(
    Props(childTrainer),
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

  private lazy val router = Router(SmallestMailboxRoutingLogic(), children)

  override def receive: Receive = noTrainingDataAvailable

  private def noTrainingDataAvailable: Receive = {
    case SendTrainingData(stockData) =>
      unstashAll()
      context.become(training(stockData))
    case GetStd | GetAvg | StartTraining | IsEverythingDone =>
      sender() ! NoTrainingDataReceived
    case _ =>
      stash() //for async functions
  }

  private def common(trainingDataInput: StockDataResponse): Receive = {
    case StartTraining =>
      router.route(Train(trainingDataInput), sender())
  }

  private def training(trainingDataInput: StockDataResponse): Receive = common(trainingDataInput) orElse {
    case GetStd | GetAvg | IsEverythingDone =>
      sender() ! NotComputed
    case Terminated(ref) =>
      createNewChildWhenTerminated(ref)
    case Trained =>
      router.removeRoutee(sender())
      unstashAll()
      context.become(trained(trainingDataInput, Seq(sender())))
    case _ =>
      stash()
  }

  private def trained(trainingDataInput: StockDataResponse, actors: Seq[ActorRef]): Receive = common(trainingDataInput) orElse {
    case Trained =>
      router.removeRoutee(sender())
      val trainedActors = actors :+ sender()
      if(trainedActors.size == noOfChildren) context.become(completed(trainingDataInput, trainedActors))
      else context.become(trained(trainingDataInput, trainedActors))
    case Terminated(ref) =>
      createNewChildWhenTerminated(ref)
      self ! Died(ref)
      self ! StartTraining
    case Died(ref) if actors.contains(ref) =>
      context.become(trained(trainingDataInput, actors.diff(Seq(ref))))
    case GetStd =>
      computePortfolios(actors).map(mean[Double]) pipeTo sender()
    case GetAvg =>
      computePortfolios(actors).map(portfolios => portfolios.sum / portfolios.size) pipeTo sender()
    case IsEverythingDone =>
      sender() ! NotCompleted
  }

  private def completed(trainingDataInput: StockDataResponse, actors: Seq[ActorRef]): Receive = {
    case IsEverythingDone =>
      sender() ! Completed
  }

  private def computePortfolios(actors: Seq[ActorRef]): Future[Seq[Double]] = Future
      .sequence(actors.map(_ ? GetPortfolio).map(_.mapTo[TrainerData]))
      .map(_.flatMap { case TrainingData(p) => Some(p); case _ => None })

  private def createNewChildWhenTerminated(ref: ActorRef): ActorRef = {
    router.removeRoutee(ref)
    val newChild = context.actorOf(childProps)
    context.watch(newChild)
    router.addRoutee(newChild)
    newChild
  }

  def mean[T](xs: Iterable[T])(implicit T: Fractional[T]): T = T.div(xs.sum, T.fromInt(xs.size))
}