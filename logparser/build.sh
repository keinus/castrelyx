#!/bin/bash
VERSION=$(grep "^version" build.gradle | head -n 1 | sed -E "s/version\s*=\s*['\"]([^'\"]+)['\"]/\1/")
echo $VERSION

./gradlew build

sudo docker build . --build-arg APP_VERSION=$VERSION --tag=keinus/logparser:$VERSION
sudo docker tag keinus/logparser:$VERSION 192.168.50.22:5000/logparser:$VERSION
sudo docker tag keinus/logparser:$VERSION 192.168.50.22:5000/logparser:latest
sudo docker push 192.168.50.22:5000/logparser:$VERSION
sudo docker push 192.168.50.22:5000/logparser:latest
