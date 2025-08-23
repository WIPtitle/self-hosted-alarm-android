# Webapp wrapper android application

This is a base project to create an android application based on a web application with a simple
Ntfy client included to receive push notifications.

This is not intended to be used as-is, but it shows the isolated steps needed to receive and display
push notifications with Ntfy if interested in a single topic.

A cool, more thoughtful implementation of this project could be an android application that almost remove the 
need to develop a real application for a self hosted webapp, without having to deal with non self-hosted services.

For example, you can create your own webapp and instead of having to worry about setting up Firebase you can
access it from this wrapper and connect it to your self-hosted Ntfy server given topic and credentials.