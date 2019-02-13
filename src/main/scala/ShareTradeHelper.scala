import java.time.LocalDate

import SharePriceGetter.{RequestStockPrice, StockDataResponse}
import TrainerRouterActor._
import akka.actor.{ActorSystem, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps

object ShareTradeHelper extends App {
  val system = ActorSystem("ShareTradeHelperSystem")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(10 seconds)

  val budget = 2400.0
  val noOfStocks = 0

  val stockPriceListRequest = RequestStockPrice("lloy", LocalDate.of(2005, 1,1), LocalDate.of(2015, 1, 1))
  val policyActor = system.actorOf(QDecisionPolicyActor.props, "Q-policy-actor")

  val sharePriceGetter = system.actorOf(Props[SharePriceGetter], "Share-price-getter-actor")
  val stockPrices: Future[StockDataResponse] = (sharePriceGetter ? stockPriceListRequest).mapTo[StockDataResponse]
  val trainerRouterActor = system.actorOf(TrainerRouterActor.props(policyActor, budget, noOfStocks), "Trainer-parent-actor")
  stockPrices.map(SendTrainingData) pipeTo trainerRouterActor
  trainerRouterActor ! StartTraining

  (0 to 100).find(_ => { //This is not an web server and blocking is ok here, waiting until Tensorflow simulates final results.
      Thread.sleep(1000)
      Await.result[TrainerState]((trainerRouterActor ? IsEverythingDone).mapTo[TrainerState], 5 seconds) match {
        case Completed => true
        case _ => false
      }})

  val avgFuture = (trainerRouterActor ? GetAvg).mapTo[Double]
  val stdFuture = (trainerRouterActor ? GetStd).mapTo[Double]

  for {
    avg <- avgFuture
    std <- stdFuture
  } yield println(s"avg is $avg, std is $std")
}


