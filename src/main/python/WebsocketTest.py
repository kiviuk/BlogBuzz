#!/usr/bin/env python3
# Note: chmod +x src/main/python/WebsocketTest.py
import asyncio
import websockets
import requests

PORT = 8888
EP_HTTP = f"http://localhost:{PORT}/greet"
EP_WS = f"ws://localhost:{PORT}/subscribe/v1"


def test_http_endpoint():
    response = requests.get(f"${EP_HTTP}/Emily")
    print(f"HTTP Response: {response.text}")
    assert response.text == "Greetings Emily!", "HTTP endpoint test failed"

async def test_websocket():
    async with websockets.connect(EP_WS) as websocket:

        await websocket.send("Hi!")
        response = await websocket.recv()
        print(f"Received: {response}")
        assert response == "Received: Hi!", "no hi! :("

        await websocket.send("bye!")
        try:
            await asyncio.wait_for(websocket.recv(), timeout=2.0)
        except (asyncio.TimeoutError, websockets.exceptions.ConnectionClosed):
            print("socket closed 'bye!'")
        else:
            raise AssertionError("socket no close :(")

async def main():
    test_http_endpoint()

    await test_websocket()

    print("Ok!")

if __name__ == "__main__":
    asyncio.run(main())