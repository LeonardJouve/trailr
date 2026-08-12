package proto

import (
	context "context"
	"errors"

	"google.golang.org/grpc"
	client "google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

type Client struct {
	client     TrailSolverClient
	connection *client.ClientConn
}

var instance *Client

func New(uri string) (*Client, error) {
	connection, err := grpc.NewClient(
		uri,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		return nil, err
	}

	instance = &Client{
		connection: connection,
		client:     NewTrailSolverClient(connection),
	}

	return instance, nil
}

func GetInstance() (*Client, error) {
	if instance == nil {
		return nil, errors.New("no database")
	}

	return instance, nil
}

func (c *Client) Close() error {
	return c.connection.Close()
}

func (c *Client) SolveTour(ctx context.Context, req *SolveTourRequest) (*SolveTourResponse, error) {
	return c.client.SolveTour(ctx, req)
}
