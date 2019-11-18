#pragma once

#include <arrow/compute/context.h>
#include <arrow/status.h>
#include <gandiva/node.h>
#include <gandiva/node_visitor.h>
#include <iostream>

#include <memory>
#include <unordered_map>
#include "codegen/arrow_compute/ext/kernels_ext.h"
#include "codegen/code_generator.h"
#include "codegen/common/visitor_base.h"

namespace sparkcolumnarplugin {
namespace codegen {
namespace arrowcompute {

class ExprVisitor;
class BuilderVisitor;

using ExprVisitorMap = std::unordered_map<std::string, std::shared_ptr<ExprVisitor>>;
using ArrayList = std::vector<std::shared_ptr<arrow::Array>>;
enum class ArrowComputeResultType { Array, ArrayList, Batch, BatchList, None };
enum class BuilderVisitorNodeType { FunctionNode, FieldNode };

class BuilderVisitor : public VisitorBase {
 public:
  BuilderVisitor(std::shared_ptr<arrow::Schema> schema_ptr,
                 std::shared_ptr<gandiva::Node> func, ExprVisitorMap* expr_visitor_cache)
      : schema_(schema_ptr), func_(func), expr_visitor_cache_(expr_visitor_cache) {}
  ~BuilderVisitor() {}
  arrow::Status Eval() {
    RETURN_NOT_OK(func_->Accept(*this));
    return arrow::Status::OK();
  }
  arrow::Status GetResult(std::shared_ptr<ExprVisitor>* out);
  std::string GetResult();
  BuilderVisitorNodeType GetNodeType() { return node_type_; }

 private:
  arrow::Status Visit(const gandiva::FunctionNode& node) override;
  arrow::Status Visit(const gandiva::FieldNode& node) override;
  // input
  std::shared_ptr<arrow::Schema> schema_;
  std::shared_ptr<gandiva::Node> func_;
  // output
  std::shared_ptr<ExprVisitor> expr_visitor_;
  std::string field_name_;
  BuilderVisitorNodeType node_type_;
  // ExprVisitor Cache, used when multiple node depends on same node.
  ExprVisitorMap* expr_visitor_cache_;
};

class ExprVisitor {
 public:
  ExprVisitor(std::shared_ptr<arrow::Schema> schema_ptr,
              const gandiva::FunctionNode* func,
              std::vector<std::string> param_field_names);

  ExprVisitor(std::shared_ptr<arrow::Schema> schema_ptr,
              const gandiva::FunctionNode* func,
              std::vector<std::string> param_field_names,
              std::shared_ptr<ExprVisitor> dependency);
  ~ExprVisitor() = default;

  arrow::Status Eval(const std::shared_ptr<arrow::RecordBatch>& in);
  arrow::Status Execute();
  arrow::Status Reset();

  ArrowComputeResultType GetResultType();
  arrow::Status GetResult(std::shared_ptr<arrow::Array>* out,
                          std::vector<std::shared_ptr<arrow::Field>>* out_fields);
  arrow::Status GetResult(ArrayList* out,
                          std::vector<std::shared_ptr<arrow::Field>>* out_fields);
  arrow::Status GetResult(std::vector<ArrayList>* out, std::vector<int>* out_sizes,
                          std::vector<std::shared_ptr<arrow::Field>>* out_fields);

 private:
  // Input data holder.
  std::shared_ptr<arrow::Schema> schema_;
  const gandiva::FunctionNode* func_;
  std::shared_ptr<ExprVisitor> dependency_;
  std::shared_ptr<arrow::RecordBatch> in_record_batch_;
  std::vector<std::string> param_field_names_;

  // Input data from dependency.
  ArrowComputeResultType dependency_result_type_ = ArrowComputeResultType::None;
  std::vector<std::shared_ptr<arrow::Field>> in_fields_;
  std::vector<ArrayList> in_batch_array_;
  std::vector<int> in_batch_size_array_;
  ArrayList in_batch_;
  ArrayList in_array_list_;
  std::shared_ptr<arrow::Array> in_array_;

  // Output data types.
  ArrowComputeResultType return_type_ = ArrowComputeResultType::None;
  // This is used when we want to output an Array after evaluate.
  std::shared_ptr<arrow::Array> result_array_;
  // This is used when we want to output an ArrayList after evaluation.
  ArrayList result_array_list_;
  ArrayList result_batch_;
  // This is used when we want to output an BatchList after evaluation.
  std::vector<ArrayList> result_batch_list_;
  std::vector<int> result_batch_size_list_;
  // Return fields
  std::vector<std::shared_ptr<arrow::Field>> result_fields_;

  // Long live variables
  arrow::compute::FunctionContext ctx_;
  class Impl;
  std::unique_ptr<Impl> impl_;
};

arrow::Status MakeExprVisitor(std::shared_ptr<arrow::Schema> schema_ptr,
                              std::shared_ptr<gandiva::Expression> expr,
                              ExprVisitorMap* expr_visitor_cache,
                              std::shared_ptr<ExprVisitor>* out);

}  // namespace arrowcompute
}  // namespace codegen
}  // namespace sparkcolumnarplugin
