#!/bin/bash

export SBT_OPTS="-XX:+UseNUMA -XX:-UseBiasedLocking -Xms3024M -Xmx3048M -Xss1M -XX:MaxPermSize=256m -XX:+UseParallelGC"
sbt "$@"


