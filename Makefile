# SPDX-License-Identifier: MIT OR Apache-2.0
.PHONY: bootstrap build test lint format format-check demo benchmark generate policy validate python native android dotnet

bootstrap:
	./scripts/bootstrap

build:
	./scripts/build

test:
	./scripts/test

lint:
	./scripts/lint

format:
	./scripts/format

format-check:
	./scripts/format --check

demo:
	./scripts/demo

benchmark:
	./scripts/benchmark

generate:
	./scripts/generate

policy:
	./scripts/lint repository

python:
	./scripts/test python

native:
	./scripts/test native

android:
	./scripts/test android

dotnet:
	./scripts/test dotnet

validate: policy
	./scripts/lint python
	./scripts/test python
	.venv/bin/python -m conceptflow_mpl_protocol.validation
	./scripts/demo
	./scripts/benchmark --iterations 100
	./scripts/build python
	.venv/bin/python scripts/repository/check_wheels.py
