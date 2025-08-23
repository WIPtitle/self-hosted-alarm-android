# Self-hosted alarm service android app

This android project is a fork of the more generic [webapp-wrapper-android](https://github.com/WIPtitle/webapp-wrapper-android).

It consist of a simple fullscreen webview used to access the self-hosted alarm service webapp on a dedicated app instead of
opening a tab in a browser; it also automatically fetches Ntfy credentials and opens a websocket to the correct Ntfy server
to receive and show real time push notifications without having to rely on a not self-hosted solution.

## Installation

Just build the apk and install it.

## How to use

If not yet configured the app will ask for your alarm service url: simply insert it (must be a public one if you want to access alarm server outside your network)
with port if necessary (for example, http://100.200.100.200).

Confirm it, and if everything is reachable and correctly configured you should now be able to use the app as a native android app, with push
notifications served by Ntfy.

There is an always-on notification used to keep the connection open, you can manually hide it without losing the connection.
