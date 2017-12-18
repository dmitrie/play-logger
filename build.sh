#!/bin/bash

rm -fr dist
play dependencies --sync || exit $?
play build-module || exit $?