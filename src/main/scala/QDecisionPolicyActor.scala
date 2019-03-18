import java.io.{BufferedInputStream, FileInputStream}
import java.nio.file.Paths

import QDecisionPolicyActor._
import akka.actor.{Actor, ActorLogging, Props}
import org.platanios.tensorflow.api.core.Shape
import org.platanios.tensorflow.api.core.client.Session
import org.platanios.tensorflow.api.learn.hooks.{CheckpointSaver, SummarySaver}
import org.platanios.tensorflow.api.ops.variables.Saver
import org.platanios.tensorflow.api.tensors.Tensor
import org.platanios.tensorflow.api.{Output, tf}
import org.tensorflow.framework.MetaGraphDef

import scala.util.Random

object QDecisionPolicyActor {
  val actions = Seq(Buy, Sell, Hold)
  val inputDim = 201 + 2 // history + budget + shares
  val epsilon = 0.9f
  val gamma = 0.001f
  val outputDim: Int = actions.size
  val h1Dim = 200

  def props = Props[QDecisionPolicyActor]

  sealed trait Action
  case object Buy extends Action
  case object Sell extends Action
  case object Hold extends Action

  sealed trait DecisionPolicy
  case class SelectionAction(currentState: Tensor[Float], step: Float) extends DecisionPolicy
  case class UpdateQ(state: Tensor[Float], reward: Float, nextState: Tensor[Float]) extends DecisionPolicy
  case object Updated extends DecisionPolicy
}

class QDecisionPolicyActor extends Actor with ActorLogging {
  private val x: Output[Float] = tf.placeholder[Float](Shape(-1, inputDim))
  private val y: Output[Float] = tf.placeholder[Float](Shape(outputDim))

  private val w1 = tf.variable[Float]("weight1", Shape(inputDim, h1Dim), tf.RandomNormalInitializer())
  private val b1 = tf.constant[Float](Tensor(0.1f), Shape(h1Dim))
  private val h1 = tf.relu(tf.matmul(x, w1) + b1)

  private val w2 = tf.variable[Float]("weight2", Shape(h1Dim, outputDim), tf.RandomNormalInitializer())
  private val b2 = tf.constant[Float](Tensor(0.1f), Shape(outputDim))
  private val q: Output[Float] = tf.relu(tf.matmul(h1, w2) + b2)

  private val loss = tf.square(y - q)
  private val trainOp = tf.train.AdaGrad(0.01f).minimize(loss)

  override def receive: Receive = iterateUpdate(initialiseSession(), 0)

  private def iterateUpdate(session: Session, iteration: Long): Receive = {

    case SelectionAction(currentState, _) if currentState.size != inputDim =>
      throw new IllegalArgumentException(s"SelectionAction received from ${sender().path.parent.name}, but tensorflow input size($inputDim) and state(${currentState.size}) size do not match")
    case SelectionAction(currentState, step) if Random.nextFloat() < Seq(epsilon, step / 1000f).min => //this returns BUY or SELL or HOLD
      val actionQVals = session.run(feeds = Map(x -> currentState), q)
      sender() ! actions(actionQVals.reshape(Shape(-1)).argmax(0).scalar.toInt)
    case SelectionAction(_, _) =>
      sender() ! actions(0 + Random.nextInt(actions.length))

    case UpdateQ(state, _, nextState) if state.size != inputDim || nextState.size != inputDim =>
      throw new IllegalArgumentException(s"update q received from ${sender().path.parent.name}, but tensorflow input size($inputDim) and state(or nextState)${state.size} size do not match")
    case UpdateQ(state, reward, nextState) =>
      val actionQVals = session.run(feeds = Map(x -> state), q)
      val nextActionQVals: Tensor[Float] = session.run(feeds = Map(x -> nextState), q)
      val indexNext = nextActionQVals.reshape(Shape(-1)).argmax(0).scalar.toInt
      val specificIndexValueToBeUpdated: Float = reward + gamma * nextActionQVals(0)(indexNext).scalar
      val newActionQVals: Tensor[Float] = assignValueToTensorCoordinate(actionQVals, 0, indexNext, specificIndexValueToBeUpdated)
      val updatedFeeds = Map(x -> state, y -> newActionQVals)
      session.run(feeds = updatedFeeds, targets = trainOp)
      if(iteration % 500 == 0 && iteration != 0) { saveSnapshot((session, iteration)) }
      sender() ! Updated
      context.become(iterateUpdate(session, iteration + 1))
  }

  private def initialiseSession(): Session = {
    val sess = Session()
    sess.run(targets = tf.globalVariablesInitializer())
    sess
  }

  private def assignValueToTensorCoordinate(input: Tensor[Float], x: Int, y: Int, replacingValue: Float): Tensor[Float] = {
    val Array(m, n) = input.shape.asArray
    val seq = Seq.tabulate(m, n)((i, j) => input(i, j).scalar)
    seq.updated(x , seq(x).updated(y, replacingValue))
  }

  private def saveSnapshot(sessionInfo: (Session, Long)) = {

  }

}