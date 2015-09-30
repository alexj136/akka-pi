package main

import akka.actor._
import syntax.Name
import interpreter._

object Main extends App {
  val system: ActorSystem = ActorSystem("PiSystem")
  val ch = system.actorOf(Props[Channel], name = "ch")
  val dm = system.actorOf(Props(new Dummy(ch)), name = "dm")
  dm ! new Name(0)
  ch ! ChanPut(new Name(1))
}
