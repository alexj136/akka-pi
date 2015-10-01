package interpreter

import akka.actor._
import syntax._

class PiLauncher(p: Pi) {

  var running: Boolean = false

  val system: ActorSystem = ActorSystem("PiLauncher")

  def go: Unit = {
    if (!this.running) {
      this.running = true
      val initChanMap: Map[Name, ActorRef] =
        ((p free) map { case n => (n, system.actorOf(Props[Channel])) }).toMap
      system.actorOf(Props(classOf[PiRunner], initChanMap, p)) ! PiGo
    }
    else throw new RuntimeException("Go'd a running PiLauncher")
  }

  def kill: Unit = {
    system.shutdown()
    this.running = false
  }
}

// Signalling object sent to PiRunners to tell them to compute
case object PiGo

// Runs a Pi process
class PiRunner(var chanMap: Map[Name, ActorRef], var proc: Pi) extends Actor {

  def awaitDeliveryConfirmation: Receive = {
    case ChanTaken => {
      context.unbecome()
      self ! PiGo
    }
  }
  
  var bindResponseTo: Option[Name] = None
  def awaitResponse: Receive = {
    case ChanGet(channel) => {
      this.chanMap = this.chanMap.updated(this.bindResponseTo.get, channel)
      this.bindResponseTo = None
      context.unbecome()
      self ! PiGo
      println("received")
    }
  }

  def receive: Receive = {
    case PiGo => this.proc match {
      case Par(p, q   ) => {
        val newRunner: ActorRef =
          context.actorOf(Props(classOf[PiRunner], this.chanMap, q))
        this.proc = p
        self ! PiGo
        newRunner ! PiGo
      }
      case Rcv(c, b, p) => {
        this.chanMap(c) ! ChanAsk
        this.proc = p
        this.bindResponseTo = Some(b)
        context become awaitResponse
      }
      case Srv(c, b, p) => {
        this.chanMap(c) ! ChanAsk
        this.proc = p
        this.bindResponseTo = Some(b)
        val newRunner: ActorRef =
          context.actorOf(Props(classOf[PiRunner], this.chanMap, this.proc))
        newRunner ! PiGo
        context become awaitResponse
      }
      case Snd(c, m, p) => {
        println("sending")
        this.chanMap(c) ! ChanGive(this.chanMap(m))
        this.proc = p
        context become awaitDeliveryConfirmation
      }
      case New(c, p   ) => {
        val channel: ActorRef = context.actorOf(Props[Channel])
        this.chanMap = this.chanMap.updated(c, channel)
        this.proc = p
        self ! PiGo
      }
      case End          => {
        println("process ending")
        context stop self
      }
    }
  }
}

// Queries sent by PiRunners to Channels
sealed abstract class ChanQuery
// Precursor to a ChanTaken ChanQueryResponse
case class  ChanGive(channel: ActorRef) extends ChanQuery
// Precursor to a ChanGet ChanQueryResponse
case object ChanAsk                     extends ChanQuery

// Responses sent by Channels to PiRunners
sealed abstract class ChanQueryResponse
// Complements a ChanGive ChanQuery
case object ChanTaken                   extends ChanQueryResponse
// Complements a ChanAsk ChanQuery
case class  ChanGet(channel: ActorRef)  extends ChanQueryResponse

// Implements the behaviour of channels in pi calculus
class Channel extends Actor {
  
  var curMsgAndSender: Option[(ActorRef, ActorRef)] = None
  def curMsg: Option[ActorRef] = curMsgAndSender map (_._1)
  def curSender: Option[ActorRef] = curMsgAndSender map (_._2)

  def holding: Receive = {
    case ChanAsk => {
      sender ! ChanGive(this.curMsg.get)
      this.curSender.get ! ChanTaken
      this.curMsgAndSender = None
      context.unbecome()
    }
  }

  def receive: Receive = {
    case ChanGive(msg) => {
      this.curMsgAndSender = Some((msg, sender))
      context become holding
    }
  }
}
