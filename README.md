# BlogBuzz

A ZIO-based web application featuring WebSocket functionality.

## Features

- WebSocket support for real-time dashboard updates
- Event driven modular architecture
- Testable :)

## Tested with

- Scala 3.6.2
- ZIO 2.1.9
- openjdk 17.0.9
- Thorium (release 8)

## How to use

1. Clone the repo
2. Run ```sbt``` from the project root directory to launch the sbt console.
3. Enter ```run``` to start the app from the sbt console.
4. Navigate to <http://localhost:8888/static/blogbuzz.html>
5. You should see a welcome message, when you are connected to the websocket server.
6. The server is now waiting for interaction.
7. Hit **ENTER** in the server console to start crawling for messages.
8. Hit **ENTER** again to stop crawling and stop the server at any time.

## What's happening?

1. Messages / blog post data will appear in the browser in real time as they are
received from the WordPress backend.
2. Incoming messages will display the word count map (top 10) as well as
additional metadata related to the blogpost. The browser will automatically
sort incoming messages by the WordPress post modifiedDateGmt.
3. Run `wscat -c ws://localhost:8888/subscribe/v2 | tee output.txt` from your
terminal to additionally receive the `unsorted JSON data`.
4. The crawler is tested and configured to fetch items from <https://wptavern.com>.
5. After all messages have been consumed by the backend, polling for
new messages enters a 'cooling' period. Polling will occur once every
scheduler.maxCoolDownScale * scheduler.intervalInSec, compared to every
scheduler.intervalInSec. An additional ping post will be published at the same
rate for as long as there are no new blog posts. This is to prevent the
server connection from shutting down and to keep the UI action happening.
You can see the latest ping post coming in on top of the list in the browser.

## Files

1. Config files are stored in `src/main/resources`
2. The 'web app' html code is stored in `src/main/resources/webapp/blogbuzz.html`
3. **The server app supports an environment variable `ENV_VAR`**
    - `dev`, `prod`
    - `test` = **DEFAULT**

4. **The app reads configurations based on the `ENV_VAR` environment variable:**
    - `dev`: `src/main/resources/application-dev.yaml`
    - **`test` (default)**: `src/main/resources/application-test.yaml`
    - `prod`: `src/main/resources/application-prod.yaml`

    - Main configuration options:
        - **intervalInSec**: the interval at which the WordPress backend is
        polled for new posts.
        - **startDateGmt**: the initial lower bound date for fetching posts.
        - **port**: Port for the WebSocket and HTTP server.
        - **subscribePath**: WebSocket subscription path.
        - **subscribePath** is also hardcoded in `src/main/resources/webapp/blogbuzz.html`

## Architecture

The app is made up of the following components:

1. A **CrawlerService** which is responsible for fetching blog posts from
the WordPress backend.
2. A **heartbeat event queue**, which is internally used to periodically
trigger the crawler to fetch new blog posts.
3. A **scheduler**, which is responsible for submitting those timestamp
events to the queue.
4. An **outbound hub**, which is shared between the crawling fibers and
the websocket server, to publish blog posts as they arrive.
5. And 2 smaller components: a **timestamp listener**, which controls the crawler,
and finally the **WebSocket server**, responsible to serve the
blog posts to connected clients.

   - Note: the scheduler stops emitting events when the crawler is in progress to
   prevent running 2 crawlers at  the same time, but mainly to demonstrate
   how fibers can react to each other.
   - Note: while there is only 1 CrawlerService active at any time,
   the CrawlerService itself uses multiple threads/fibers to concurrently
   fetch blog posts leveraging the WordPress pagination API. The 'complexity'
   of the crawler is hidden away from the rest of the application logic.
   - There is one data transfer object for the inbound blog post and one for
   the outbound blog post. The latter  automatically converts between the
   2 and also provides the sorted word-count map.

## Future Improvements

1. **Features**
    - Add support for a REST endpoint to fetch historic blog posts.
    - Use websockets to push only new blog posts to clients.
    - Explore webhooks/websub alternatives for notifying clients of new blog posts.

2. **Security**

    - WebSocket connections are currently unsecured. Authentication and
    authorization mechanisms should be added to prevent unauthorized access.

3. **Performance Monitoring**

    - GC performance and memory usage needs (more) testing.
    - JSON conversion could benefit from upickle to improve speed and memory efficiency.
    - Handling of server side rate limiting.

4. **Persistence**

    - Currently, the server does not maintain any state beyond its in-memory storage.
    - Integration with a distributed database or cache system is
    mandatory in a cloud environment.

5. **Miscellaneous**

    - Replace custom code with standard libraries (eg configuration management, validation).
    - Support for exponential/predictive cool-off strategy.
    - Cool-off params should be configurable (done).
    - Fully master and leverage ZIO features.
    - Dockerization and cloud readiness.
    - Streaming
    - Observability / Metrics
    - Support schema evolution (v1 -> v2) using ZIO Schema
