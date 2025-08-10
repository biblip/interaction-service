#!/bin/bash
docker stop is-container
docker rm is-container
docker build -t interaction-service .
docker run -d --restart=always -p 4242:4242 --name is-container interaction-service
