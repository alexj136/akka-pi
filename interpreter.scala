package interpreter

import akka.actor._
import syntax._

class PiLauncher(p: Pi) {

  var running: Boolean = false

  val system: ActorSystem = ActorSystem("PiLauncher")

  def go: Unit = {
    if (!this.running) {
      this.running = true
      system.actorOf(Props(classOf[PiRunner], free p, p)) ! PiGo
    }
    else throw new RuntimeException("Go'd a running PiLauncher")
  }

  def kill: Unit = {
    system shutdown
    this.running = false
  }
}

// Signalling object sent to PiRunners to tell them to compute
case object PiGo

// Runs a Pi process
class PiRunner(var chanMap: Map[Name, ActorRef], var proc: Pi) extends Actor {

  def awaitDeliveryConfirmation: Receive = {
    case ChanTaken => {
      become receive
      self ! PiGo
    }
  }
  
  var bindResponseTo: Option[Name] = None
  def awaitResponse: Receive = {
    case ChanGet(channel) => {
      this.chanMap = this.chanMap.updated(this.bindResponseTo.get, channel)
      this.bindResponseTo = None
      become receive
      self ! PiGo
    }
  }

  def receive: Receive = {
    case PiGo => this.proc match {
      case Par(p, q   ) => {
        val newRunner: ActorRef =
          context.actorOf(Props(classOf[PiRunner], this.chanMap, q))
        newRunner ! PiGo
        this.proc = p
        self ! PiGo
      }
      case Rcv(c, b, p) => {
        this.chanMap(c) ! ChanAsk
        this.proc = p
        this.bindResponseTo = Some(b)
        become awaitResponse
      }
      case Srv(c, b, p) => {
        this.chanMap(c) ! ChanAsk
        this.proc = p
        this.bindResponseTo = Some(b)
        val newRunner: ActorRef =
          context.actorOf(Props(classOf[PiRunner], this.chanMap, this.proc))
        newRunner ! PiGo
        become awaitResponse
      }
      case Snd(c, m, p) => {
        this.chanMap(c) ! ChanGive(this.chanMap(m))
        this.proc = p
        become awaitDeliveryConfirmation
      }
      case New(c, p   ) => {
        val channel: ActorRef = context.actorOf(Props[Channel])
        this.chanMap = this.chanMap.updated(c, channel)
        this.proc = p
        self ! PiGo
      }
      case End          => context stop self
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
case class  ChanTaken                   extends ChanQueryResponse
// Complements a ChanAsk ChanQuery
case object ChanGet(channel: ActorRef)  extends ChanQueryResponse

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
      become receive
    }
  }

  def receive: Receive = {
    case ChanGive(msg, sender) => {
      this.curMsgAndSender = Some((msg, sender))
      become holding
    }
  }
}
