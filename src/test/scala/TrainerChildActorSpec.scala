import java.time.LocalDate
import java.time.chrono.ChronoLocalDate

import QDecisionPolicyActor.{SelectionAction, Sell}
import SharePriceGetter.StockDataResponse
import TrainerChildActor.{GetPortfolio, Initialise, Train}
import TrainerRouterActor.{NotComputed, Trained, TrainingData}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{EventFilter, ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, OneInstancePerTest, WordSpecLike}

import scala.collection.immutable.TreeMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class TrainerChildActorSpec extends TestKit(ActorSystem("TrainerActorSpec", ConfigFactory.parseString("""
  akka.loggers = ["akka.testkit.TestEventListener"]"""))) with ImplicitSender with WordSpecLike with BeforeAndAfterAll with OneInstancePerTest {

  implicit val ex: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(20 seconds)
  implicit val localDateOrdering: Ordering[LocalDate] = Ordering.by(identity[ChronoLocalDate])

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "Trainer actor" should {
    "throw invalid input error when StockDataResponse contains a different number of stock prices from the number of Tensor input nodes" in {
      val policyProbe = TestProbe()
      val sharePrices = (55 until 100).map(i => LocalDate.of(2001, 7, 10).plusDays(i) -> i.toDouble)
      val stockData = StockDataResponse("my-share", TreeMap(sharePrices.toArray: _*))
      val trainer = system.actorOf(Props(new TrainerChildActor(policyProbe.ref, 2000, 0)), "trainer-actor-test")

      EventFilter[IllegalArgumentException](occurrences = 1) intercept {
        trainer ! Train(stockData)
      }
    }

    "return NotComputed at initial stage when it receives GetPortpolio message" in {
      val policyProbe = TestProbe()
      val trainer = system.actorOf(Props(new TrainerChildActor(policyProbe.ref, 2000, 0)), "trainer-actor-test")
      trainer ! GetPortfolio
      expectMsg(NotComputed)
    }

    "return TrainingData at initial stage when it receives Train message" in {
      normalTrainedCase()
    }

    "return NotComputed at rollback stage when it receives GetPortfolio message" in {
      val trainer = normalTrainedCase()
      trainer ! Initialise
      trainer ! GetPortfolio
      expectMsg(NotComputed)
    }

  }

  private def normalTrainedCase(): ActorRef = {
    val sharePrices = (55 until 257).map(i => LocalDate.of(2001, 7, 10).plusDays(i) -> i.toDouble)
    val stockData = StockDataResponse("my-share", TreeMap(sharePrices.toArray: _*))
    assert(sharePrices.size >= 202)
    val parent = TestProbe()
    val policyProbe = TestProbe()
    val trainer = TestActorRef(Props(new TrainerChildActor(policyProbe.ref, 2000, 0)), parent.ref, "ChildActor")

    trainer ! Train(stockData)
    policyProbe.expectMsgType[SelectionAction]
    policyProbe.reply(Sell)

    parent.expectMsg(Trained)
    trainer ! GetPortfolio
    expectMsgType[TrainingData]

    trainer
  }
}
