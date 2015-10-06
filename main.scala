package main

import akka.actor._
import syntax._
import interpreter._

object Main extends App {
  new PiLauncher(Par(Snd(Name(1), Name(2), End),Rcv(false, Name(1), Name(3), End)))
}
