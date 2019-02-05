import java.time.LocalDate

import SharePriceGetter.{RequestStockPrice, StockDataResponse}
import TrainerRouterActor.{GetAvg, GetStd, Train}
import akka.actor.{ActorSystem, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

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
  val trainerActor = system.actorOf(TrainerRouterActor.props(policyActor, budget, noOfStocks), "Trainer-parent-actor")
  stockPrices.map(Train) pipeTo trainerActor //cluster distributed data
  val avgFuture = (trainerActor ? GetAvg).mapTo[Double]
  val stdFuture = (trainerActor ? GetStd).mapTo[Double]

  for {
    avg <- avgFuture
    std <- stdFuture
  } yield println(s"avg is $avg, std is $std")
}
