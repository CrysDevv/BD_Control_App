#!/bin/sh
# Compila y corre las pruebas del núcleo del protocolo y compila el simulador (requiere g++).
set -e
cd "$(dirname "$0")"
# capacidad pequeña (8) para probar el desborde del buffer con pocas lecturas
g++ -std=c++11 -Wall -Wextra -Werror -fsanitize=address,undefined -g -DBD_BUFFER_CAPACITY=8 -o /tmp/bd_test_core test_core.cpp
/tmp/bd_test_core
# el simulador usa la capacidad real del firmware
g++ -std=c++11 -Wall -Wextra -Werror -O1 -o /tmp/bd_host_sim host_sim.cpp
echo "simulador compilado en /tmp/bd_host_sim"
