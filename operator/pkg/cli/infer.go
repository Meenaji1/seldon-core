package cli

import (
	"bytes"
	"context"
	"fmt"
	"io/ioutil"
	"math"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"encoding/json"

	grpc_retry "github.com/grpc-ecosystem/go-grpc-middleware/retry"
	"github.com/seldonio/seldon-core/operatorv2/scheduler/apis/mlops/v2_dataplane"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/encoding/protojson"
)

const (
	SeldonModelHeader    = "seldon-model"
	SeldonPipelineHeader = "pipeline"
)

type InferType uint32

const (
	InferModel InferType = iota
	InferPipeline
	InferExplainer
)

type InferenceClient struct {
	host        string
	port        int
	httpClient  *http.Client
	callOptions []grpc.CallOption
	counts      map[string]int
}

type V2Error struct {
	Error string `json:"error"`
}

type V2InferenceResponse struct {
	ModelName    string                 `json:"model_name,omitempty"`
	ModelVersion string                 `json:"model_version,omitempty"`
	Id           string                 `json:"id"`
	Parameters   map[string]interface{} `json:"parameters,omitempty"`
	Outputs      []interface{}          `json:"outputs,omitempty"`
}

func NewInferenceClient(host string, port int) *InferenceClient {
	opts := []grpc.CallOption{
		grpc.MaxCallSendMsgSize(math.MaxInt32),
		grpc.MaxCallRecvMsgSize(math.MaxInt32),
	}
	return &InferenceClient{
		host:        host,
		port:        port,
		httpClient:  http.DefaultClient,
		callOptions: opts,
		counts:      make(map[string]int),
	}
}

func (ic *InferenceClient) getConnection() (*grpc.ClientConn, error) {
	retryOpts := []grpc_retry.CallOption{
		grpc_retry.WithBackoff(grpc_retry.BackoffExponential(100 * time.Millisecond)),
	}
	opts := []grpc.DialOption{
		grpc.WithInsecure(),
		grpc.WithStreamInterceptor(grpc_retry.StreamClientInterceptor(retryOpts...)),
		grpc.WithUnaryInterceptor(grpc_retry.UnaryClientInterceptor(retryOpts...)),
	}
	conn, err := grpc.Dial(fmt.Sprintf("%s:%d", ic.host, ic.port), opts...)
	if err != nil {
		return nil, err
	}
	return conn, nil
}

func (ic *InferenceClient) getUrl(path string) *url.URL {
	return &url.URL{
		Scheme: "http",
		Host:   net.JoinHostPort(ic.host, strconv.Itoa(ic.port)),
		Path:   path,
	}
}

func (ic *InferenceClient) call(resourceName string, path string, data []byte, inferType InferType) ([]byte, error) {
	v2Url := ic.getUrl(path)
	req, err := http.NewRequest("POST", v2Url.String(), bytes.NewBuffer(data))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	switch inferType {
	case InferModel:
		req.Header.Set(SeldonModelHeader, resourceName)
	case InferPipeline:
		req.Header.Set(SeldonModelHeader, fmt.Sprintf("%s.%s", resourceName, SeldonPipelineHeader))
	}

	response, err := ic.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	b, err := ioutil.ReadAll(response.Body)
	if err != nil {
		return nil, err
	}
	err = response.Body.Close()
	if err != nil {
		return nil, err
	}
	if response.StatusCode != http.StatusOK {
		if response.StatusCode == http.StatusBadRequest {
			v2Error := V2Error{}
			err := json.Unmarshal(b, &v2Error)
			if err != nil {
				return nil, err
			}
			return nil, fmt.Errorf("%s", v2Error.Error)
		} else {
			return nil, fmt.Errorf("V2 server error: %s", b)
		}
	}
	return b, nil
}

func (ic *InferenceClient) updateSummary(modelName string) {
	if count, ok := ic.counts[modelName]; ok {
		ic.counts[modelName] = count + 1
	} else {
		ic.counts[modelName] = 1
	}
}

func (ic *InferenceClient) InferRest(resourceName string, data []byte, showRequest bool, showResponse bool, iterations int, inferType InferType) error {
	if showRequest {
		printPrettyJson(data)
	}
	path := fmt.Sprintf("/v2/models/%s/infer", resourceName)
	for i := 0; i < iterations; i++ {
		res, err := ic.call(resourceName, path, data, inferType)
		if err != nil {
			return err
		}
		v2InferResponse := V2InferenceResponse{}
		err = json.Unmarshal(res, &v2InferResponse)
		if err != nil {
			return err
		}
		if iterations == 1 {
			if showResponse {
				printPrettyJson(res)
			}
		} else {
			ic.updateSummary(v2InferResponse.ModelName)
		}
	}
	if iterations > 1 {
		fmt.Printf("%v\n", ic.counts)
	}
	return nil
}

func (ic *InferenceClient) InferGrpc(resourceName string, data []byte, showRequest bool, showResponse bool, iterations int, inferType InferType) error {
	req := &v2_dataplane.ModelInferRequest{}
	err := protojson.Unmarshal(data, req)
	if err != nil {
		return err
	}
	req.ModelName = resourceName
	if showRequest {
		printProto(req)
	}
	conn, err := ic.getConnection()
	if err != nil {
		return err
	}
	grpcClient := v2_dataplane.NewGRPCInferenceServiceClient(conn)
	ctx := context.TODO()
	switch inferType {
	case InferModel:
		ctx = metadata.AppendToOutgoingContext(ctx, SeldonModelHeader, resourceName)
	case InferPipeline:
		ctx = metadata.AppendToOutgoingContext(ctx, SeldonPipelineHeader, resourceName)
	}

	for i := 0; i < iterations; i++ {
		res, err := grpcClient.ModelInfer(ctx, req)
		if err != nil {
			return err
		}
		if iterations == 1 {
			if showResponse {
				printProto(res)
			}
		} else {
			ic.updateSummary(res.ModelName)
		}
	}
	if iterations > 1 {
		fmt.Printf("%v\n", ic.counts)
	}
	return nil
}

func (ic *InferenceClient) Infer(modelName string, inferMode string, data []byte, showRequest bool, showResponse bool, iterations int, inferType InferType) error {
	switch inferMode {
	case "rest":
		return ic.InferRest(modelName, data, showRequest, showResponse, iterations, inferType)
	case "grpc":
		return ic.InferGrpc(modelName, data, showRequest, showResponse, iterations, inferType)
	default:
		return fmt.Errorf("Unknown infer mode - needs to be grpc or rest")
	}
}