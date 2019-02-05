import QDecisionPolicyActor.{SelectionAction, Sell}
import TrainerChildActor.GetPortfolio
import TrainerRouterActor.NotComputed
import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Success

class TrainerChildActorSpec extends TestKit(ActorSystem("TrainerActorSpec"))
  with ImplicitSender with WordSpecLike with BeforeAndAfterAll {

  implicit val ex: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(20 seconds)

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  val policyProbe = TestProbe()
  val sa = SelectionAction(_, _)
  val future = policyProbe.ref ? sa
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
      val trainer = system.actorOf(Props(new TrainerChildActor(policyProbe.ref, 2000, 0)), "trainer-actor")
//      trainer ! Train(stockData)
//      expectMsg(NotComputed)
    }



  }

}
