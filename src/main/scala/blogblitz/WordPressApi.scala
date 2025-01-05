package blogblitz

import zio.json.*

import java.time.Instant

// WordPress API integration
// https://developer.wordpress.org/rest-api/reference/posts/
object WordPressApi {
  val MAX_PER_PAGE_BY_WP = 100

  // WordPress GMT timestamps are in a non-standard format, should be ISO-8601
  implicit val instantGmtDecoder: JsonDecoder[Instant] = {
    JsonDecoder[String].mapOrFail { str =>
      // Append 'Z' to match ISO-8601
      val isoStr = if (str.endsWith("Z")) then str else s"${str}Z"
      try Right(Instant.parse(isoStr))
      catch {
        case ex: Exception => Left(s"Failed to parse Instant: $ex")
      }
    }
  }

  // A unique type for GMT fields to distinguish them from local ones
  opaque type GmtInstant = Instant
  object GmtInstant {
    def apply(instant: Instant): GmtInstant        = instant
    extension (gmt: GmtInstant) def value: Instant = gmt

    implicit val decoder: JsonDecoder[GmtInstant] =
      instantGmtDecoder.map(GmtInstant(_))

  }

  // Inbound WordPress post
  // Note on DDD: This is a wire model not the actual domain object
  case class BlogPost(
    // The title of the post.
    title: Title,
    // The content of the post.
    content: Content,
    // Unique identifier of the post (140881).
    id: Int,
    // The globally unique identifier for the post (https://wptavern.com/?p=140881).
    guid: Guid,
    // The URL to the post (https://wptavern.com/classicpress-community-votes-to-re-fork-wordpress).
    link: String,
    // The date the post was published in WordPress, as GMT.
    @jsonField("date_gmt") publishedDateGmt: GmtInstant,
    // The date the post was last modified in WordPress, as GMT.
    @jsonField("modified_gmt") modifiedDateGmt: GmtInstant,
    // The date the post was imported in Blog Blitz, as GMT.
    importDateTime: Instant = Instant.now(),
    // The request URL used to fetch the post (http://wptavern.com?per_page=10&after=...)
    @jsonExclude requestUrl: String = "")

  case class Title(
    rendered: String)

  case class Content(
    rendered: String)

  case class Guid(
    rendered: String)

  object Title {
    implicit val decoder: JsonDecoder[Title] = DeriveJsonDecoder.gen[Title]

  }

  object Content {
    implicit val decoder: JsonDecoder[Content] = DeriveJsonDecoder.gen[Content]

  }

  object Guid {
    implicit val decoder: JsonDecoder[Guid] = DeriveJsonDecoder.gen[Guid]

  }

  object BlogPost {
    implicit val decoder: JsonDecoder[BlogPost] =
      DeriveJsonDecoder.gen[BlogPost]

  }
  case class OutboundBlogPost(
    id: Int,
    title: String,
    link: String,
    guid: String,
    content: Option[String],
    publishedDateGmt: Instant,
    modifiedDateGmt: Instant,
    importDateTime: Instant,
    requestUrl: String,
    wordCountMap: Option[Seq[(String, Int)]] = None)
      derives JsonEncoder,
              JsonDecoder {
    def this(blogPost: BlogPost) = this(
      id = blogPost.id,
      guid = blogPost.guid.rendered,
      title = blogPost.title.rendered,
      link = blogPost.link,
      content = Some(blogPost.content.rendered),
      publishedDateGmt = blogPost.publishedDateGmt,
      modifiedDateGmt = blogPost.modifiedDateGmt,
      importDateTime = blogPost.importDateTime,
      requestUrl = blogPost.requestUrl,
      wordCountMap = None,
    )

    def toJson: String = this.toJsonPretty

  }

  extension (blogPost: BlogPost) {
    def toOutboundBlogPost: OutboundBlogPost = {
      OutboundBlogPost(
        id = blogPost.id,
        guid = blogPost.guid.rendered,
        title = blogPost.title.rendered,
        link = blogPost.link,
        content = Some(blogPost.content.rendered),
        publishedDateGmt = blogPost.publishedDateGmt,
        modifiedDateGmt = blogPost.modifiedDateGmt,
        importDateTime = blogPost.importDateTime,
        requestUrl = blogPost.requestUrl,
      )
    }
    def withSortedWordCountMap: OutboundBlogPost = {

      // https://www.browserling.com/tools/word-frequency
      val words = blogPost
        .content
        .rendered
        .split("\\W+") // simple split on words
        .map(_.toLowerCase)
        .filter(_.nonEmpty)

      val wordCountMap = words.groupMapReduce(identity)(_ => 1)(_ + _)

      val sortedWordCountMap = wordCountMap.toSeq.sortBy(-_._2)

      // replace content with word count
      blogPost
        .toOutboundBlogPost
        .copy(wordCountMap = Some(sortedWordCountMap), content = None)
    }

  }

  // Ping post to signal that we are still alive even if there are no new posts
  def pingPost(): BlogPost = {
    object PingPostDefaults {
      val title = WordPressApi.Title("No new posts, still I am alive! Repeat: I AM STILL ALIVE!")
      val content =
        WordPressApi.Content("No new posts still I am alive Repeat I AM STILL ALIVE")
      val guid = WordPressApi.Guid("ping-post")
    }

    val now    = Instant.now()
    val nowGmt = GmtInstant(now)

    WordPressApi.BlogPost(
      id = Math.abs(now.toEpochMilli.hashCode),
      title = PingPostDefaults.title,
      content = PingPostDefaults.content,
      guid = PingPostDefaults.guid,
      link = "/ping",
      publishedDateGmt = nowGmt,
      modifiedDateGmt = nowGmt,
      importDateTime = now,
    )
  }

}
