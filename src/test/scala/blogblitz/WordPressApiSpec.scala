package blogblitz

import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import java.time.Instant
import blogblitz.WordPressApi.*

object WordPressApiSpec extends ZIOSpecDefault {

  // Sample JSON to decode
  val sampleJson: String =
    """
    {
      "id": 140881,
      "title": { "rendered": "Test Title" },
      "content": { "rendered": "This is a test post with a few content." },
      "guid": { "rendered": "https://example.com/?p=140881" },
      "link": "https://example.com/test-title",
      "date_gmt": "2023-10-05T12:00:00",
      "modified_gmt": "2023-10-06T14:30:00",
      "importDateTime": "2023-10-07T14:30:00",
      "requestUrl": "https://example.com?per_page=10&after=..."
    }
    """

  def spec = suite("WordPressApiSpec")(
    test("should decode BlogPost from JSON correctly") {
      val decoded = sampleJson.fromJson[BlogPost]

      println(decoded)

      assert(decoded)(
        isRight(
          equalTo(
            BlogPost(
              id = 140881,
              title = Title(rendered = "Test Title"),
              content = Content(rendered = "This is a test post with a few content."),
              guid = Guid(rendered = "https://example.com/?p=140881"),
              requestUrl = "https://example.com?per_page=10&after=...",
              link = "https://example.com/test-title",
              publishedDateGmt = GmtInstant(Instant.parse("2023-10-05T12:00:00Z")),
              modifiedDateGmt = GmtInstant(Instant.parse("2023-10-06T14:30:00Z")),
              importDateTime = Instant.parse("2023-10-07T14:30:00Z"),
            )
          )
        )
      )
    },
    test("should convert BlogPost to OutboundBlogPost correctly") {
      val decoded  = sampleJson.fromJson[BlogPost]
      val outbound = decoded.map(_.toOutboundBlogPost)

      assert(outbound)(
        isRight(
          equalTo(
            OutboundBlogPost(
              id = 140881,
              title = "Test Title",
              link = "https://example.com/test-title",
              guid = "https://example.com/?p=140881",
              content = Some("This is a test post with a few content."),
              publishedDateGmt = Instant.parse("2023-10-05T12:00:00Z"),
              modifiedDateGmt = Instant.parse("2023-10-06T14:30:00Z"),
              importDateTime = Instant.parse("2023-10-07T14:30:00Z"),
              requestUrl = "https://example.com?per_page=10&after=...",
            )
          )
        )
      )
    },
    test("should generate a correct word count map JSON for OutboundBlogPost content") {

      val decoded             = sampleJson.fromJson[BlogPost]
      val wordSortedWordCount = decoded.map(_.withSortedWordCountMap)

      assert(wordSortedWordCount)(
        isRight(
          equalTo(
            OutboundBlogPost(
              id = 140881,
              title = "Test Title",
              link = "https://example.com/test-title",
              guid = "https://example.com/?p=140881",
              content = None,
              publishedDateGmt = Instant.parse("2023-10-05T12:00:00Z"),
              modifiedDateGmt = Instant.parse("2023-10-06T14:30:00Z"),
              importDateTime = Instant.parse("2023-10-07T14:30:00Z"),
              requestUrl = "https://example.com?per_page=10&after=...",
              wordCountMap = Some(
                Seq(
                  "a"       -> 2,
                  "this"    -> 1,
                  "is"      -> 1,
                  "content" -> 1,
                  "post"    -> 1,
                  "test"    -> 1,
                  "few"     -> 1,
                  "with"    -> 1,
                )
              ),
            )
          )
        )
      )

    },
    test("should generate a correct word count map JSON for BlogPost content") {
      val decoded          = sampleJson.fromJson[BlogPost]
      val withWordCountMap = decoded.map(_.withSortedWordCountMap)

      assert(withWordCountMap)(
        isRight(
          equalTo(
            OutboundBlogPost(
              id = 140881,
              title = "Test Title",
              link = "https://example.com/test-title",
              guid = "https://example.com/?p=140881",
              content = None,
              publishedDateGmt = Instant.parse("2023-10-05T12:00:00Z"),
              modifiedDateGmt = Instant.parse("2023-10-06T14:30:00Z"),
              importDateTime = Instant.parse("2023-10-07T14:30:00Z"),
              requestUrl = "https://example.com?per_page=10&after=...",
              wordCountMap = Some(
                Seq(
                  "a"       -> 2,
                  "this"    -> 1,
                  "is"      -> 1,
                  "content" -> 1,
                  "post"    -> 1,
                  "test"    -> 1,
                  "few"     -> 1,
                  "with"    -> 1,
                )
              ),
            )
          )
        )
      )
    },
    test("should decode GmtInstant correctly") {
      val gmtInstantJson = "\"2023-10-05T12:00:00\""

      val decoded = gmtInstantJson.fromJson[GmtInstant]
      assert(decoded)(isRight(equalTo(GmtInstant(Instant.parse("2023-10-05T12:00:00Z")))))
    },
    test("should parse ISO-8601 compliant Instant strings without modification") {
      val isoCompliantJson = "\"2023-10-06T14:30:00Z\""
      val result           = isoCompliantJson.fromJson[Instant]

      assert(result)(isRight(equalTo(Instant.parse("2023-10-06T14:30:00Z"))))
    },
    test("should parse non-ISO-8601 WordPress timestamps by appending 'Z'") {
      val nonIsoJson = "\"2023-10-06T14:30:00\""
      val result     = nonIsoJson.fromJson[Instant]

      assert(result)(isRight(equalTo(Instant.parse("2023-10-06T14:30:00Z"))))
    },
    test("should produce Left when given invalid timestamp format") {
      val invalidJson = "\"not-a-timestamp\""
      val result      = invalidJson.fromJson[Instant]

      assert(result)(isLeft(Assertion.containsString("Failed to parse Instant")))
    },
  )

}
