#!/usr/bin/env python3
# Note: chmod +x src/main/python/WebsocketTest.py
import asyncio
import websockets

PORT = 8888
EP_WS = f"ws://localhost:{PORT}/subscribe/v2"


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
    await test_websocket()

    print("Ok!")


if __name__ == "__main__":
    asyncio.run(main())

