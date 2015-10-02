package interpreter

import akka.actor._
import syntax._

class PiLauncher(p: Pi) {

  val (system: ActorSystem, creationManager: ActorRef) = {

    val sys: ActorSystem = ActorSystem("PiLauncher")
    val initChanMap: Map[Name, ActorRef] =
      (p.free map { case n => (n, sys actorOf Props[Channel]) }).toMap
    val creationManager: ActorRef = sys actorOf Props[PiCreationManager]
    creationManager ! MakeRunner(initChanMap, p)

    (sys, creationManager)
  }
}

// Serves as parent actor for other actors
class PiCreationManager extends Actor {

  def receive: Receive = {
    case MakeChannel => {
      val channel: ActorRef = context.actorOf(Props[Channel])
      sender ! MakeChannelResponse(channel)
    }
    case MakeRunner(chanMap, p) => {
      context.actorOf(Props(classOf[PiRunner], chanMap, p, self)) ! PiGo
    }
  }
}

sealed abstract class CreationRequest
case object MakeChannel extends CreationRequest
case class  MakeRunner(chanMap: Map[Name, ActorRef], p: Pi)
  extends CreationRequest

case class MakeChannelResponse(channel: ActorRef)

// Signalling object sent to PiRunners to tell them to compute
case object PiGo

// Runs a Pi process
class PiRunner(
  var chanMap: Map[Name, ActorRef],
  var proc: Pi,
  val creationManager: ActorRef) extends Actor {

  var bindResponseTo: Option[Name] = None
  def awaitResponse: Receive = {
    case ChanGet(channel) => {
      this.chanMap = this.chanMap.updated(this.bindResponseTo.get, channel)
      this.bindResponseTo = None
      context.unbecome()
      self ! PiGo
    }
  }

  def receive: Receive = {
    case PiGo => this.proc match {
      case Par(p, q   ) => {
        this.creationManager ! MakeRunner(this.chanMap, q)
        this.proc = p
        self ! PiGo
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
        this.creationManager ! MakeRunner(this.chanMap, this.proc)
        context become awaitResponse
      }
      case Snd(c, m, p) => {
        this.chanMap(c) ! ChanGive(this.chanMap(m))
        this.proc = p
        context.become({ case ChanTaken => {
          context.unbecome()
          self ! PiGo
        }})
      }
      case New(c, p   ) => {
        this.creationManager ! MakeChannel
        context.become({ case MakeChannelResponse(channel) => {
          this.chanMap = this.chanMap.updated(c, channel)
          this.proc = p
          context.unbecome()
          self ! PiGo
        }})
      }
      case End          => {
        println("process ending")
        self ! PoisonPill
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
      println("ChanAsk at channel")
      sender ! ChanGet(this.curMsg.get)
      this.curSender.get ! ChanTaken
      this.curMsgAndSender = None
      context.unbecome()
    }
  }

  def receive: Receive = {
    case ChanGive(msg) => {
      println("ChanGive at channel")
      this.curMsgAndSender = Some((msg, sender))
      context become holding
    }
  }
}
