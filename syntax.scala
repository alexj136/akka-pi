package syntax

case class Name(val id: Int)

sealed abstract class Pi {
  def free: Set[Name] = this match {
    case Par(p, q   ) => (free p) union (free q)
    case Rcv(c, b, p) => ((free p) - b) + c
    case Srv(c, b, p) => ((free p) - b) + c
    case Snd(c, m, p) => ((free p) + m) + c
    case New(c, p   ) => (free p) - c
    case End          => Set empty
  }
}

case class  Par(p: Pi  , q: Pi         ) extends Pi
case class  Rcv(c: Name, b: Name, p: Pi) extends Pi
case class  Srv(c: Name, b: Name, p: Pi) extends Pi
case class  Snd(c: Name, m: Name, p: Pi) extends Pi
case class  New(c: Name, p: Pi         ) extends Pi
case object End                          extends Pi
