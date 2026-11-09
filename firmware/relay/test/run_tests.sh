#!/bin/sh
# Compila y corre las pruebas del modo seguro del relé (requiere g++).
set -e
cd "$(dirname "$0")"
g++ -std=c++11 -Wall -Wextra -Werror -fsanitize=address,undefined -g -o /tmp/bd_test_failsafe test_failsafe.cpp
/tmp/bd_test_failsafe
