# Inference Artifacts

To run your model inside Seldon you must supply an inference artifact that can be downloaded and run on one of MLServer or Triton inference servers. We list artifacts below by alphabetical order below.

```{list-table}
:header-rows: 1

* - Type
  - Server
  - Tag
  - Server Docs
  - Example
* - Alibi-Detect
  - MLServer
  - `alibi-detect`
  - [docs](https://docs.seldon.io/projects/alibi-detect/en/stable/)
  - [example](../../examples/cifar10.md)
* - Alibi-Explain
  - MLServer
  - `alibi-explain`
  - [docs](https://docs.seldon.io/projects/alibi/en/stable/)
  - [example](../../examples/explainer-examples.md)
* - DALI
  - Triton
  - `dali`
  - [docs](https://github.com/triton-inference-server/dali_backend)
  - TBC
* - Huggingface
  - MLServer
  - `huggingface`
  - [docs](https://github.com/SeldonIO/MLServer/blob/master/docs/examples/huggingface/README.md)
  - [example](../../examples/huggingface.md)
* - LightGBM
  - MLServer
  - `lightgbm`
  - [docs](https://github.com/SeldonIO/MLServer/blob/master/docs/examples/lightgbm/README.md)
  - TBC
* - MLFlow
  - MLServer
  - `mlflow`
  - [docs](https://github.com/SeldonIO/MLServer/tree/master/runtimes/mlflow)
  - TBC
* - ONNX
  - Triton
  - `onnx`
  - [docs](https://github.com/triton-inference-server/onnxruntime_backend)
  - TBC
* - OpenVino
  - Triton
  - `openvino`
  - [docs](https://github.com/triton-inference-server/openvino_backend)
  - TBC
* - Custom Python
  - MLServer
  - `python, mlserver`
  - [docs](https://github.com/SeldonIO/MLServer/blob/master/docs/examples/custom/README.md)
  - [example](../../examples/pandasquery.md)
* - Custom Python
  - Triton
  - `python, triton`
  - [docs](https://github.com/triton-inference-server/python_backend)
  - TBC  
* - PyTorch
  - Triton
  - `pytorch`
  - [docs](https://github.com/triton-inference-server/pytorch_backend)
  - TBC  
* - SKLearn
  - MLServer
  - `python`
  - [docs](https://github.com/SeldonIO/MLServer/blob/master/docs/examples/sklearn/README.md)
  - [example](../../examples/income.md)
* - Spark Mlib
  - MLServer
  - `spark-mlib`
  - [docs](https://github.com/SeldonIO/MLServer/tree/master/runtimes/mllib)
  - TBC
* - Tensorflow
  - Triton
  - `tensorflow`
  - [docs](https://github.com/triton-inference-server/tensorflow_backend)
  - [example](../../examples/cifar10.md)
* - TensorRT
  - Triton
  - `tensorrt`
  - [docs](https://github.com/triton-inference-server/tensorrt_backend)
  - TBC
* - Triton FIL
  - Triton
  - `fil`
  - [docs](https://github.com/triton-inference-server/fil_backend)
  - TBC
* - XGBoost
  - MLServer
  - `xgboost`
  - [docs](https://github.com/SeldonIO/MLServer/blob/master/docs/examples/xgboost/README.md)
  - TBC
```

## Creating MLServer artifacts

For [MLServer](https://github.com/SeldonIO/MLServer) targeted models you will need to create a model-settings.json file to help MLServer load your model and place this alongside your artifact. See the [MLServer project](https://mlserver.readthedocs.io/en/latest/reference/model-settings.html)  for details.


## Creating Triton artifacts

For [Triton inference server](https://github.com/triton-inference-server/server) models you will need to create [a configuration config.pbtxt file](https://github.com/triton-inference-server/server/blob/main/docs/user_guide/model_configuration.md) alongside your artifact.

## Notes

 * The `tag` field represents the tag you need to add to the `requirements` part of the Model spec for your artifact to be loaded on a compatible server. e.g. for an sklearn model:
   ```{literalinclude} ../../../../../samples/models/sklearn-iris-gs.yaml 
   :language: yaml
   ```

