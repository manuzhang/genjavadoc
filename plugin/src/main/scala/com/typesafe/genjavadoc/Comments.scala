package com.typesafe.genjavadoc

import scala.collection.immutable.TreeMap
import scala.tools.nsc.ast.parser.SyntaxAnalyzer
import scala.annotation.tailrec
import scala.reflect.internal.util.RangePosition

trait Comments { this: TransformCake ⇒
  import global._

  def unit: CompilationUnit

  object parser extends {
    val global: Comments.this.global.type = Comments.this.global
    val runsAfter = List[String]()
    val runsRightAfter = None
  } with SyntaxAnalyzer

  val replacements = Seq(
    "{{{" -> "<pre><code>",
    "}}}" -> "</code></pre>",
    "“" -> "&ldquo;",
    "”" -> "&rdquo;",
    "‘" -> "&lsquo;",
    "’" -> "&rsquo;",
    "[[" -> "{@link ",
    "]]" -> "}")
  val EmptyLine = """(?:/\*\*(?:.*\*/)?|\s+(?:\*/|\*?))\s*""".r
  val See = """(.*@see )\[\[([^]]+)]]\s*""".r

  case class Comment(pos: Position, text: Seq[String])
  object Comment {
    def apply(pos: Position, text: String) = {
      val ll = text.replaceAll("\n[ \t]*", "\n ").split("\n")
        .map {
          case See(prefix, link) ⇒ prefix + link
          case x                 ⇒ x
        }
        .map(line ⇒ (line /: replacements) { case (l, (from, to)) ⇒ l.replace(from, to) })
      val (_, _, _, l2) = ((false, false, true, List.empty[String]) /: ll) {
        // insert <p> line upon transition to empty, collapse contiguous empty lines
        case ((pre, code, empty, lines), line @ EmptyLine()) ⇒
          val nl =
            if (pre || line.contains("/**") || line.contains("*/")) line :: lines
            else if (!pre && !empty) " * <p>" :: (lines.head + (if (code) "</code>" else "")) :: lines.tail
            else lines
          (pre, false, true, nl)
        case ((pre, code, empty, lines), line) ⇒
          val (nc, nl) = if (pre) (code, line) else codeLine(code, line)
          val np = if (line contains "<pre>") true else if (line contains "</pre>") false else pre
          val nl2 = if (pre && np) preLine(nl) else nl
          (np, nc, false, nl2 :: lines)
      }
      new Comment(pos, l2.reverse map htmlEntity)
    }
    private def preLine(line: String): String =
      line.replace("@", "&#64;").replace("<", "&lt;").replace(">", "&gt;")
    @tailrec private def codeLine(code: Boolean, line: String): (Boolean, String) = {
      val next = replace(line, "`", if (code) "</code>" else "<code>")
      if (next eq line) (code, line)
      else codeLine(!code, next)
    }
    private def replace(str: String, from: String, to: String): String = {
      str.indexOf(from) match {
        case -1 ⇒ str
        case n  ⇒ str.substring(0, n) + to + str.substring(n + from.length)
      }
    }
    private def htmlEntity(str: String): String = {
      // Workaround for SI-8091
      str flatMap (ch ⇒ if (ch > 127) "&#x%04x;".format(ch.toInt) else "" + ch)
    }
  }
  var pos: Position = rangePos(unit.source, 0, 0, 0)

  implicit val positionOrdering: Ordering[Position] = new Ordering[Position] {
    def compare(a: Position, b: Position) =
      if (a.endOrPoint < b.startOrPoint) -1
      else if (a.startOrPoint > b.endOrPoint) 1
      else 0
  }
  var comments = TreeMap[Position, Comment]()

  new parser.UnitParser(unit) {
    override def newScanner = new parser.UnitScanner(unit) {
      private var docBuffer: StringBuilder = null // buffer for comments (non-null while scanning)
      private var inDocComment = false // if buffer contains double-star doc comment

      override protected def putCommentChar() {
        if (inDocComment)
          docBuffer append ch

        nextChar()
      }
      override def skipDocComment(): Unit = {
        inDocComment = true
        docBuffer = new StringBuilder("/**")
        super.skipDocComment()
      }
      override def skipBlockComment(): Unit = {
        inDocComment = false
        docBuffer = new StringBuilder("/*")
        super.skipBlockComment()
      }
      override def skipComment(): Boolean = {
        // emit a block comment; if it's double-star, make Doc at this pos
        def foundStarComment(start: Int, end: Int) = try {
          val str = docBuffer.toString
          val pos = new RangePosition(unit.source, start, start, end)
          comments += pos -> Comment(pos, str)
          true
        } finally {
          docBuffer = null
          inDocComment = false
        }
        super.skipComment() && ((docBuffer eq null) || foundStarComment(offset, charOffset - 2))
      }
    }
  }.parse()

  val positions = comments.keySet

  def between(p1: Position, p2: Position) = unit.source.content.slice(p1.startOrPoint, p2.startOrPoint).filterNot(_ == '\n').mkString

  object ScalaDoc extends (Comment ⇒ Boolean) {
    def apply(c: Comment): Boolean = c.text.head.startsWith("/**")
  }

}
