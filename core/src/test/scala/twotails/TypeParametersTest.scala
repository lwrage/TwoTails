package twotails

import org.scalatest.{ FlatSpec, Matchers }

//first test arg types in the class constructor
class Fiji[A]{
  @mutualrec final def thing(x: A, y: Int): A = if(0 < y) thing(x, y-1) else x
}

class Bahama[A]{
  @mutualrec final def one(x: A, y: Int): A = if(0 < y) two(x, y-1) else x
  @mutualrec final def two(x: A, y: Int): A = if(0 < y) one(x, y-1) else x
}

class KeyLargo{
  @mutualrec final def thing[A](x: A, y: Int): A = if(0 < y) thing(x, y-1) else x
}

class Burmuda{
  @mutualrec final def one[A](x: A, y: Int): A = if(0 < y) two(x, y-1) else x
  @mutualrec final def two[A](x: A, y: Int): A = if(0 < y) one(x, y-1) else x
}

class Montego[A]{
  @mutualrec final def one[B](x: A, y: B, z: Int): (A, B) = if(0 < z) two(x, y, z-1) else (x, y)
  @mutualrec final def two[B](x: A, y: B, z: Int): (A, B) = if(0 < z) one(x, y, z-1) else (x, y)
}

class Cayman{
  @mutualrec final def one[A : Numeric](x: A, y: Int): A = if(0 < y) two(x, y-1) else x
  @mutualrec final def two[A : Numeric](x: A, y: Int): A = if(0 < y) one(x, y-1) else x
}

class Aruba{
  @mutualrec final def one[A](x: A)(y: Int): A = if(0 < y) two(x)(y-1) else x
  @mutualrec final def two[A](x: A)(y: Int): A = if(0 < y) one(x)(y-1) else x
}

class Belize{
  @mutualrec final def one[A](x: A, y: Int): A = if(0 < y) x else two(x, y)
  @mutualrec final def two[A](x: A, y: Int): A = if(0 < y) one(x, y) else two(x, y-1)
}

class CostaRica{
  class Dep{
    type Out = Int
    def out: Out = 1
  }

  @mutualrec final def one(x: Int, dep: Dep): dep.Out = if(x < 0) dep.out else two(x-1, dep)
  @mutualrec final def two(x: Int, dep: Dep): dep.Out = if(x < 0) dep.out else one(x-1, dep)
}

class TypeParamtersTest extends FlatSpec with Matchers{
  val fourK = 400000

  "A single argument, annotated method of a class with a type parameter" should "be equivalent to a tailrec" in{
    val fiji = new Fiji[Int]

    fiji.thing(1, fourK) should equal (1)
  }

  "A single argument, annotated method with a type parameter" should "be equivalent to a tailrec" in{
  	val largo = new KeyLargo

  	largo.thing(1, fourK) should equal (1)
  }

  "Two mutually recursive, single argument, annotated methods of a class with a type parameter" should "not throw a StackOverflow" in{
    val b = new Bahama[String]

    b.one("one", fourK) should equal ("one")
  }

  "Two mutually recursive, annotated methods with a type parameter" should "not throw a StackOverflow" in{
    val b = new Burmuda

    b.one("yo", 7) should equal ("yo")
  }

  "Two mutually recursive methods with both class and method type parameters" should "not throw a StackOverflow" in{
    val m = new Montego[Int]

    m.one(1, 'a', fourK) should equal (1 -> 'a')
  }

  "Two mutually recursive methods with type parameters and type constraints" should "not throw a StackOverflow" in{
    val c = new Cayman

    c.one(1, fourK) should equal (1)
  }

  "Two mutually recursive, multi-argument list methods with type parameters" should "not throw a StackOverflow" in{
    val a = new Aruba

    a.one('a')(fourK) should equal ('a')
  }
}