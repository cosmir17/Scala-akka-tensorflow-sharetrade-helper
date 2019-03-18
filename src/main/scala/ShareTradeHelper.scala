import java.time.LocalDate

import SharePriceGetter.{RequestStockPrice, StockDataResponse}
import TrainerRouterActor._
import akka.actor.{ActorSystem, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps

object ShareTradeHelper extends App {
  val logger = LoggerFactory.getLogger(ShareTradeHelper.getClass)
  val system = ActorSystem("ShareTradeHelperSystem")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(20 seconds)

  val budget = 2400.0
  val noOfStocks = 0

  val stockPriceListRequest = RequestStockPrice("MSFT", LocalDate.of(1992, 1,1), LocalDate.of(2015, 1, 1))
  val policyActor = system.actorOf(QDecisionPolicyActor.props, "Q-policy-actor")

  val sharePriceGetter = system.actorOf(Props[SharePriceGetter], "Share-price-getter-actor")
  val stockPrices: Future[StockDataResponse] = (sharePriceGetter ? stockPriceListRequest).mapTo[StockDataResponse]
  val trainerRouterActor = system.actorOf(TrainerRouterActor.props(policyActor, budget, noOfStocks), "Trainer-parent-actor")
  stockPrices.map(SendTrainingData) pipeTo trainerRouterActor
  trainerRouterActor ! StartTraining

  (0 to 200).find(_ => { //This is not an web server and blocking is ok here, waiting until Tensorflow simulates the final results.
    Thread.sleep(5000)

    val completedFuture = (trainerRouterActor ? IsEverythingDone).mapTo[TrainerState]
    val avgFuture = (trainerRouterActor ? GetAvg).mapTo[TrainerState]
    val stdFuture = (trainerRouterActor ? GetStd).mapTo[TrainerState]

    val msgFuture = for {
      completed <- completedFuture
      avg <- avgFuture
      std <- stdFuture
    } yield (completed, avg, std)

    Await.result(msgFuture, 10 seconds) match {
      case (Completed, Result(avg), Result(std)) => logger.info(s"avg is $avg, std is $std"); true
      case _ => false
    }})
}