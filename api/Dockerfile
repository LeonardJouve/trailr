FROM golang:1.25-alpine AS build
WORKDIR /app

RUN apk add --no-cache protobuf git
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
RUN go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

COPY api/go.mod api/go.sum ./api/
RUN cd api && go mod download

COPY . .

RUN protoc -I=solver/src/main/proto \
    --go_out=api/src/proto \
    --go_opt=paths=source_relative \
    --go-grpc_out=api/src/proto \
    --go-grpc_opt=paths=source_relative \
    trailr.proto

RUN cd api && CGO_ENABLED=0 GOOS=linux go build -o /app/trailr-api ./src

FROM alpine:latest
RUN apk --no-cache add ca-certificates

WORKDIR /app
COPY --from=build /app/trailr-api /app/trailr-api
COPY --from=build /app/api/tiles /app/tiles

ENV ENVIRONMENT=PRODUCTION

EXPOSE 3000

CMD ["/app/trailr-api"]
