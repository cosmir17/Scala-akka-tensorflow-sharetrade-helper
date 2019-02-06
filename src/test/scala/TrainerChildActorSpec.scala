import java.time.LocalDate

import QDecisionPolicyActor.{SelectionAction, Sell}
import SharePriceGetter.StockDataResponse
import TrainerChildActor.{GetPortfolio, Initialise, Train}
import TrainerRouterActor.{NotComputed, Trained, TrainingData}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

import scala.collection.immutable.TreeMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Success

class TrainerChildActorSpec extends TestKit(ActorSystem("TrainerActorSpec"))
  with ImplicitSender with WordSpecLike with BeforeAndAfterAll {

  implicit val ex: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(20 seconds)

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val sharePrices = (55 until 255).map(i => LocalDate.of(2001, 7, 10).plusDays(i) -> i.toDouble)

  private val policyProbe = TestProbe()
  private val sa = SelectionAction(_, _)
  private val future = policyProbe.ref ? sa
  policyProbe.expectMsg(0 millis, sa)
  policyProbe.reply(Sell)
  assert(future.isCompleted && future.value == Some(Success(Sell)))

  "Trainer actor" should {
    "return NotComputed at initial stage when it receives GetPortpolio message" in {
      val trainer = system.actorOf(Props(new TrainerChildActor(policyProbe.ref, 2000, 0)), "trainer-actor")
      trainer ! GetPortfolio
      expectMsg(NotComputed)
    }

    "return TrainingData at initial stage when it receives Train message" in {
      normalTrainedCase
    }

    "return NotComputed at rollback stage when it receives GetPortfolio message" in {
      val trainer = normalTrainedCase
      trainer ! Initialise
      trainer ! GetPortfolio
      expectMsg(NotComputed)
    }

  }

  private def normalTrainedCase(): ActorRef = {
    val trainer = system.actorOf(Props(new TrainerChildActor(policyProbe.ref, 2000, 0)), "trainer-actor")
    val stockData = StockDataResponse("my-share", TreeMap(sharePrices.toArray: _*))
    trainer ! Train(stockData)
    expectMsg(Trained)
    trainer ! GetPortfolio
    expectMsg(TrainingData) //ToDo I haven't made the ML part working yet. Verification of this trained data will be done once it's working.
    trainer
  }
}
