#!/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
gradle -p "$DIR" "$@"
