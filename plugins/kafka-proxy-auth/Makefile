# Makefile for Kafka Proxy Entra ID Authentication Plugins

# Variables
GO_VERSION := 1.24.7
GO_IMAGE := golang:$(GO_VERSION)

# Build environment variables (can be overridden)
GOOS ?= $(shell go env GOOS 2>/dev/null || echo linux)
GOARCH ?= $(shell go env GOARCH 2>/dev/null || echo amd64)
CGO_ENABLED ?= 0

# Detect host architecture for Docker/Kind builds
# On Apple Silicon (arm64), we need to build for arm64 when running in Kind
HOST_ARCH := $(shell uname -m)
ifeq ($(HOST_ARCH),$(filter $(HOST_ARCH),arm64 aarch64))
    DOCKER_GOARCH := arm64
else
    DOCKER_GOARCH := amd64
endif

# Determine the working directory for container
# Get the directory where this Makefile is located
MAKEFILE_DIR := $(dir $(realpath $(firstword $(MAKEFILE_LIST))))
PROJECT_ROOT := $(shell cd $(MAKEFILE_DIR) && git rev-parse --show-toplevel 2>/dev/null || echo $(MAKEFILE_DIR))

# Check if docker/podman is available, fallback to local go if needed
CONTAINER_CMD := $(shell command -v docker 2> /dev/null || command -v podman 2> /dev/null)

# Detect if we're running on Windows (Git Bash/MSYS)
UNAME_S := $(shell uname -s 2>/dev/null || echo "Windows")
IS_WINDOWS := $(if $(findstring MINGW,$(UNAME_S)),1,$(if $(findstring MSYS,$(UNAME_S)),1,$(if $(findstring CYGWIN,$(UNAME_S)),1,0)))

ifdef CONTAINER_CMD
    # Use a fixed working directory for the kafka-proxy-auth plugin
    ifeq ($(IS_WINDOWS),1)
        # Windows-specific settings (Git Bash/MSYS)
        CONTAINER_WORK_DIR := //workspace/plugins/kafka-proxy-auth
        CONTAINER_RUN := MSYS_NO_PATHCONV=1 "$(CONTAINER_CMD)" run --rm -v "$(PROJECT_ROOT)":/workspace -w $(CONTAINER_WORK_DIR) -e GOOS=$(GOOS) -e GOARCH=$(GOARCH) -e CGO_ENABLED=$(CGO_ENABLED) $(GO_IMAGE)
    else
        # Linux/macOS settings
        CONTAINER_WORK_DIR := /workspace/plugins/kafka-proxy-auth
        CONTAINER_RUN := "$(CONTAINER_CMD)" run --rm -v "$(PROJECT_ROOT)":/workspace -w $(CONTAINER_WORK_DIR) -e GOOS=$(GOOS) -e GOARCH=$(GOARCH) -e CGO_ENABLED=$(CGO_ENABLED) $(GO_IMAGE)
    endif
    GO := $(CONTAINER_RUN) go
    GOBUILD := $(CONTAINER_RUN) go build
    GOCLEAN := $(CONTAINER_RUN) go clean
    GOTEST := $(CONTAINER_RUN) go test
    GOGET := $(CONTAINER_RUN) go get
    GOMOD := $(CONTAINER_RUN) go mod
else
    $(warning Warning: Neither podman nor docker found. Falling back to local go command.)
    GO := GOOS=$(GOOS) GOARCH=$(GOARCH) CGO_ENABLED=$(CGO_ENABLED) go
    GOBUILD := GOOS=$(GOOS) GOARCH=$(GOARCH) CGO_ENABLED=$(CGO_ENABLED) go build
    GOCLEAN := go clean
    GOTEST := go test
    GOGET := go get
    GOMOD := go mod
endif

BINARY_DIR := bin

# Set binary extension based on target OS
ifeq ($(GOOS),windows)
    BINARY_EXT := .exe
else
    BINARY_EXT :=
endif

PROVIDER_BINARY := $(BINARY_DIR)/entra-token-provider$(BINARY_EXT)
VERIFIER_BINARY := $(BINARY_DIR)/entra-token-verifier$(BINARY_EXT)
TOKEN_INFO_BINARY := $(BINARY_DIR)/entra-token-info$(BINARY_EXT)

DOCKER_IMAGE := kafka-proxy-entra-auth
DOCKER_TAG := latest

# Build targets
.PHONY: all build clean test docker-build docker-push help setup-go build-linux

all: setup-go clean test build

## setup-go: Ensure Go container image is available
setup-go:
ifdef CONTAINER_CMD
	@echo "Ensuring Go $(GO_VERSION) container image is available..."
	@"$(CONTAINER_CMD)" pull $(GO_IMAGE) >/dev/null 2>&1 || true
	@echo "Using containerized Go $(GO_VERSION) with $(CONTAINER_CMD)"
else
	@echo "Container runtime not available, checking local Go installation..."
	@go version 2>/dev/null || (echo "ERROR: Go not found. Please install Go $(GO_VERSION) or install a container runtime (podman/docker)." && exit 1)
endif

## build: Build provider, verifier and token-info binaries
build: setup-go build-provider build-verifier build-token-info

## build-provider: Build the entra-token-provider binary
build-provider: setup-go
	@echo "Building entra-token-provider..."
	@mkdir -p $(BINARY_DIR)
	$(GOBUILD) -buildvcs=false -o $(PROVIDER_BINARY) ./cmd/entra-token-provider

## build-verifier: Build the entra-token-verifier binary
build-verifier: setup-go
	@echo "Building entra-token-verifier..."
	@mkdir -p $(BINARY_DIR)
	$(GOBUILD) -buildvcs=false -o $(VERIFIER_BINARY) ./cmd/entra-token-verifier

## build-token-info: Build the entra-token-info binary
build-token-info: setup-go
	@echo "Building entra-token-info..."
	@mkdir -p $(BINARY_DIR)
	$(GOBUILD) -buildvcs=false -o $(TOKEN_INFO_BINARY) ./cmd/entra-token-info

## build-linux: Build binaries for Linux (used for Docker)
build-linux: setup-go
	@echo "Building Linux binaries for Docker (architecture: $(DOCKER_GOARCH))..."
	@mkdir -p $(BINARY_DIR)
ifdef CONTAINER_CMD
    ifeq ($(IS_WINDOWS),1)
        # Windows-specific settings (Git Bash/MSYS)
		@echo "Attempting containerized build with $(CONTAINER_CMD)..."; \
		if ! MSYS_NO_PATHCONV=1 "$(CONTAINER_CMD)" run --rm -v "$(PROJECT_ROOT)":/workspace -w //workspace/plugins/kafka-proxy-auth -e GOOS=linux -e GOARCH=$(DOCKER_GOARCH) -e CGO_ENABLED=0 $(GO_IMAGE) go build -buildvcs=false -o bin/entra-token-provider ./cmd/entra-token-provider; then \
			echo "Container build failed, falling back to local Go..."; \
			GOOS=linux GOARCH=$(DOCKER_GOARCH) CGO_ENABLED=0 go build -buildvcs=false -o bin/entra-token-provider ./cmd/entra-token-provider; \
		fi
		@if ! MSYS_NO_PATHCONV=1 "$(CONTAINER_CMD)" run --rm -v "$(PROJECT_ROOT)":/workspace -w //workspace/plugins/kafka-proxy-auth -e GOOS=linux -e GOARCH=$(DOCKER_GOARCH) -e CGO_ENABLED=0 $(GO_IMAGE) go build -buildvcs=false -o bin/entra-token-verifier ./cmd/entra-token-verifier; then \
			echo "Container build failed, falling back to local Go..."; \
			GOOS=linux GOARCH=$(DOCKER_GOARCH) CGO_ENABLED=0 go build -buildvcs=false -o bin/entra-token-verifier ./cmd/entra-token-verifier; \
		fi
		@if ! MSYS_NO_PATHCONV=1 "$(CONTAINER_CMD)" run --rm -v "$(PROJECT_ROOT)":/workspace -w //workspace/plugins/kafka-proxy-auth -e GOOS=linux -e GOARCH=$(DOCKER_GOARCH) -e CGO_ENABLED=0 $(GO_IMAGE) go build -buildvcs=false -o bin/entra-token-info ./cmd/entra-token-info; then \
			echo "Container build failed, falling back to local Go..."; \
			GOOS=linux GOARCH=$(DOCKER_GOARCH) CGO_ENABLED=0 go build -buildvcs=false -o bin/entra-token-info ./cmd/entra-token-info; \
		fi
    else
        # Linux/macOS settings
		@echo "Attempting containerized build with $(CONTAINER_CMD)..."; \
		if ! "$(CONTAINER_CMD)" run --rm -v "$(PROJECT_ROOT)":/workspace -w /workspace/plugins/kafka-proxy-auth -e GOOS=linux -e GOARCH=$(DOCKER_GOARCH) -e CGO_ENABLED=0 $(GO_IMAGE) go build -buildvcs=false -o bin/entra-token-provider ./cmd/entra-token-provider; then \
			echo "Container build failed, falling back to local Go..."; \
			GOOS=linux GOARCH=$(DOCKER_GOARCH) CGO_ENABLED=0 go build -buildvcs=false -o bin/entra-token-provider ./cmd/entra-token-provider; \
		fi
		@if ! "$(CONTAINER_CMD)" run --rm -v "$(PROJECT_ROOT)":/workspace -w /workspace/plugins/kafka-proxy-auth -e GOOS=linux -e GOARCH=$(DOCKER_GOARCH) -e CGO_ENABLED=0 $(GO_IMAGE) go build -buildvcs=false -o bin/entra-token-verifier ./cmd/entra-token-verifier; then \
			echo "Container build failed, falling back to local Go..."; \
			GOOS=linux GOARCH=$(DOCKER_GOARCH) CGO_ENABLED=0 go build -buildvcs=false -o bin/entra-token-verifier ./cmd/entra-token-verifier; \
		fi
		@if ! "$(CONTAINER_CMD)" run --rm -v "$(PROJECT_ROOT)":/workspace -w /workspace/plugins/kafka-proxy-auth -e GOOS=linux -e GOARCH=$(DOCKER_GOARCH) -e CGO_ENABLED=0 $(GO_IMAGE) go build -buildvcs=false -o bin/entra-token-info ./cmd/entra-token-info; then \
			echo "Container build failed, falling back to local Go..."; \
			GOOS=linux GOARCH=$(DOCKER_GOARCH) CGO_ENABLED=0 go build -buildvcs=false -o bin/entra-token-info ./cmd/entra-token-info; \
		fi
    endif
else
	GOOS=linux GOARCH=$(DOCKER_GOARCH) CGO_ENABLED=0 go build -buildvcs=false -o bin/entra-token-provider ./cmd/entra-token-provider
	GOOS=linux GOARCH=$(DOCKER_GOARCH) CGO_ENABLED=0 go build -buildvcs=false -o bin/entra-token-verifier ./cmd/entra-token-verifier
	GOOS=linux GOARCH=$(DOCKER_GOARCH) CGO_ENABLED=0 go build -buildvcs=false -o bin/entra-token-info ./cmd/entra-token-info
endif
	@echo "Setting execute permissions on binaries..."
	@chmod +x $(BINARY_DIR)/entra-token-provider $(BINARY_DIR)/entra-token-verifier $(BINARY_DIR)/entra-token-info 2>/dev/null || true
	@echo "Go plugins built successfully for Linux/Docker"

## clean: Clean build artifacts
clean:
	@echo "Cleaning..."
	$(GOCLEAN)
	rm -rf $(BINARY_DIR)

## test: Run all tests
test: setup-go
	@echo "Running tests..."
	$(GOTEST) -v ./...

## deps: Download and verify dependencies
deps: setup-go
	@echo "Downloading dependencies..."
	$(GOMOD) download
	$(GOMOD) verify

## tidy: Clean up module dependencies
tidy: setup-go
	@echo "Tidying module dependencies..."
	$(GOMOD) tidy

## docker-build: Build Docker image with plugins
docker-build: build-linux
	@echo "Building Docker image $(DOCKER_IMAGE):$(DOCKER_TAG) for linux/$(DOCKER_GOARCH)..."
ifdef CONTAINER_CMD
	"$(CONTAINER_CMD)" build --platform linux/$(DOCKER_GOARCH) -t $(DOCKER_IMAGE):$(DOCKER_TAG) .
else
	docker build --platform linux/$(DOCKER_GOARCH) -t $(DOCKER_IMAGE):$(DOCKER_TAG) .
endif

## docker-push: Push Docker image to registry
docker-push: docker-build
	@echo "Pushing Docker image $(DOCKER_IMAGE):$(DOCKER_TAG)..."
ifdef CONTAINER_CMD
	"$(CONTAINER_CMD)" push $(DOCKER_IMAGE):$(DOCKER_TAG)
else
	docker push $(DOCKER_IMAGE):$(DOCKER_TAG)
endif

## help: Display this help message
help:
	@echo "Kafka Proxy Entra ID Authentication Plugin Build"
	@echo ""
	@echo "Available targets:"
	@sed -n 's/^##//p' ${MAKEFILE_LIST} | column -t -s ':' | sed -e 's/^/ /'

.DEFAULT_GOAL := help
