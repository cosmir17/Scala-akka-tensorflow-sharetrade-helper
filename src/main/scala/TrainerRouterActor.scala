import SharePriceGetter.StockDataResponse
import ShareTradeHelper.system
import TrainerChildActor.GetPortfolio
import TrainerRouterActor._
import akka.actor.SupervisorStrategy.{Escalate, Restart, Resume, Stop}
import akka.actor.{Actor, ActorRef, OneForOneStrategy, Props, SupervisorStrategy, Terminated}
import akka.pattern.{Backoff, BackoffSupervisor}
import akka.routing.{ActorRefRoutee, Router, SmallestMailboxRoutingLogic}
import akka.util.Timeout
import akka.pattern.{ask, pipe}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object TrainerRouterActor {
  sealed trait Trainer
  case class Train(stockData: StockDataResponse) extends Trainer
  case object GetAvg extends Trainer
  case object GetStd extends Trainer

  sealed trait TrainerState
  case object Ready extends TrainerState
  case object Trained extends TrainerState

  sealed trait TrainerData
  case object NotComputed extends TrainerData
  case class TrainingData(portfolio: Double) extends TrainerData

  case class Died(ref: ActorRef)

  def props(policyActor: ActorRef, budget: Double, noOfStocks: Int) = Props(new TrainerRouterActor(policyActor, budget, noOfStocks))
}

class TrainerRouterActor(policyActor: ActorRef, budget: Double, noOfStocks: Int) extends Actor {
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(10 seconds)

  private val noOfChildren = 10

  private def childProps: Props = BackoffSupervisor.props(Backoff.onFailure(
    Props(new TrainerChildActor(policyActor, budget, noOfStocks)),
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

  private val children = for(i <- 0 until noOfChildren) yield {
    val child = context.actorOf(childProps, s"child_trainer_supervisor_wrapped$i")
    context.watch(child)
    ActorRefRoutee(child)
  }

  private val router = Router(SmallestMailboxRoutingLogic(), children)

  override def receive: Receive = commonState orElse {
    case Trained =>
      router.removeRoutee(sender())
      trained(Seq(sender()))
    case GetStd =>
      sender() ! NotComputed
    case GetAvg =>
      sender() ! NotComputed
    case Died =>
  }

  private def trained(actors: Seq[ActorRef]): Receive = commonState orElse {
    case Trained =>
      router.removeRoutee(sender())
      trained(actors :+ sender())
    case Died(ref) if actors.contains(ref) =>
      trained(actors.diff(Seq(ref)))
    case GetStd =>
      val availablePortfoliosFuture = Future.sequence(actors.map(actor => actor ? GetPortfolio).map(_.mapTo[TrainerData]))
      val meanFuture = availablePortfoliosFuture.map(_.flatMap{case TrainingData(p) => Some(p); case _ => None}).map(mean[Double])
      meanFuture pipeTo sender()
    case GetAvg =>
      val availablePortfoliosFuture = Future.sequence(actors.map(actor => actor ? GetPortfolio).map(_.mapTo[TrainerData]))
      val avgFuture = availablePortfoliosFuture.map(portfolios => {
          val availablePortfolios = portfolios.flatMap{case TrainingData(p) => Some(p); case _ => None}
          availablePortfolios.sum / availablePortfolios.size
      })
      avgFuture pipeTo sender()
  }

  private def commonState: Receive = {
    case message @ (_:Trainer | _:TrainerState | _:TrainerData | _@Died) =>
      router.route(message, sender())
    case Terminated(ref) =>
      router.removeRoutee(ref)
      self ! Died(ref)
      val newChild = context.actorOf(childProps)
      context.watch(newChild)
      router.addRoutee(newChild)
  }

  def mean[T](xs: Iterable[T])(implicit T: Fractional[T]): T = T.div(xs.sum, T.fromInt(xs.size))
}