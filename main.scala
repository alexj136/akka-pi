package main

import akka.actor._
import syntax._
import interpreter._

object Main extends App {
  val launcher: PiLauncher =
    new PiLauncher(Par(Snd(Name(1), Name(2), End),Rcv(Name(1), Name(3), End)))
  launcher.go
}
