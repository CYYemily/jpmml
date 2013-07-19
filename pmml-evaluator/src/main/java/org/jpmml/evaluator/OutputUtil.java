/*
 * Copyright (c) 2013 University of Tartu
 */
package org.jpmml.evaluator;

import java.util.*;

import org.jpmml.manager.*;

import org.dmg.pmml.*;

import com.google.common.base.*;
import com.google.common.collect.*;

public class OutputUtil {

	private OutputUtil(){
	}

	/**
	 * Evaluates the {@link Output} element.
	 *
	 * @param predictions Map of {@link Evaluator#getPredictedFields() predicted field} values.
	 *
	 * @return Map of {@link Evaluator#getPredictedFields() predicted field} values together with {@link Evaluator#getOutputFields() output field} values.
	 */
	static
	public Map<FieldName, Object> evaluate(Map<FieldName, ?> predictions, ModelManagerEvaluationContext context){
		ModelManager<?> modelManager = context.getModelManager();

		Map<FieldName, Object> frame = Maps.newLinkedHashMap();

		context.pushFrame(frame);

		Output output = modelManager.getOrCreateOutput();

		List<OutputField> outputFields = output.getOutputFields();
		for(OutputField outputField : outputFields){
			Object value = null;

			ResultFeatureType resultFeature = outputField.getFeature();
			switch(resultFeature){
				case PREDICTED_VALUE:
				case PROBABILITY:
				case ENTITY_ID:
				case REASON_CODE:
				case RULE_VALUE:
					{
						FieldName targetField = outputField.getTargetField();
						if(targetField == null){
							targetField = modelManager.getTargetField();
						} // End if

						if(!predictions.containsKey(targetField)){
							throw new MissingFieldException(targetField, outputField);
						}

						// Prediction results could be either simple or complex values
						value = predictions.get(targetField);
					}
					break;
				default:
					break;
			} // End switch

			switch(resultFeature){
				case PREDICTED_VALUE:
					{
						value = EvaluatorUtil.decode(value);
					}
					break;
				case TRANSFORMED_VALUE:
				case DECISION:
					{
						Expression expression = outputField.getExpression();
						if(expression == null){
							throw new InvalidFeatureException(outputField);
						}

						value = ExpressionUtil.evaluate(expression, context);
					}
					break;
				case PROBABILITY:
					{
						value = getProbability(value, outputField);
					}
					break;
				case ENTITY_ID:
					{
						value = getEntityId(value);
					}
					break;
				case REASON_CODE:
					{
						value = getReasonCode(value, outputField);
					}
					break;
				case RULE_VALUE:
					{
						value = getRuleValue(value, outputField);
					}
					break;
				default:
					throw new UnsupportedFeatureException(outputField, resultFeature);
			}

			FieldName name = outputField.getName();

			DataType dataType = outputField.getDataType();
			if(dataType != null){
				value = ParameterUtil.cast(dataType, value);
			}

			// The result of one output field becomes available to other output fields
			frame.put(name, value);
		}

		context.popFrame();

		Map<FieldName, Object> result = Maps.newLinkedHashMap(predictions);
		result.putAll(frame);

		return result;
	}

	static
	private Double getProbability(Object object, final OutputField outputField){

		if(!(object instanceof HasProbability)){
			throw new EvaluationException();
		}

		HasProbability hasProbability = (HasProbability)object;

		return hasProbability.getProbability(outputField.getValue());
	}

	static
	private String getEntityId(Object object){

		if(!(object instanceof HasEntityId)){
			throw new EvaluationException();
		}

		HasEntityId hasEntityId = (HasEntityId)object;

		return hasEntityId.getEntityId();
	}

	static
	public String getReasonCode(Object object, final OutputField outputField){

		if(!(object instanceof HasReasonCodes)){
			throw new EvaluationException();
		}

		HasReasonCodes hasReasonCodes = (HasReasonCodes)object;

		int rank = outputField.getRank();
		if(rank <= 0){
			throw new InvalidFeatureException(outputField);
		}

		int index = (rank - 1);

		List<String> reasonCodes = hasReasonCodes.getReasonCodes();
		if(reasonCodes.size() > 0){

			if(index < reasonCodes.size()){
				return reasonCodes.get(index);
			}

			// The last meaningful explanation
			return reasonCodes.get(reasonCodes.size() - 1);
		}

		return null;
	}

	static
	public Object getRuleValue(Object object, final OutputField outputField){

		if(!(object instanceof HasRuleValues)){
			throw new EvaluationException();
		}

		HasRuleValues hasRuleValues = (HasRuleValues)object;

		List<AssociationRule> associationRules = hasRuleValues.getRuleValues(outputField.getAlgorithm());

		Comparator<AssociationRule> comparator = new Comparator<AssociationRule>(){

			private OutputField.RankBasis rankBasis = outputField.getRankBasis();

			private OutputField.RankOrder rankOrder = outputField.getRankOrder();


			@Override
			public int compare(AssociationRule left, AssociationRule right){
				int order;

				switch(this.rankBasis){
					case CONFIDENCE:
						order = (left.getConfidence()).compareTo(right.getConfidence());
						break;
					case SUPPORT:
						order = (left.getSupport()).compareTo(right.getSupport());
						break;
					case LIFT:
						order = (left.getLift()).compareTo(right.getLift());
						break;
					case LEVERAGE:
						order = (left.getLeverage()).compareTo(right.getLeverage());
						break;
					case AFFINITY:
						order = (left.getAffinity()).compareTo(right.getAffinity());
						break;
					default:
						throw new UnsupportedFeatureException(outputField, this.rankBasis);
				} // End switch

				switch(this.rankOrder){
					case ASCENDING:
						return order;
					case DESCENDING:
						return -order;
					default:
						throw new UnsupportedFeatureException(outputField, this.rankOrder);
				}
			}
		};
		Collections.sort(associationRules, comparator);

		String isMultiValued = outputField.getIsMultiValued();

		// Return a single result
		if("0".equals(isMultiValued)){

			int rank = outputField.getRank();
			if(rank <= 0){
				throw new InvalidFeatureException(outputField);
			}

			int index = (rank - 1);
			if(index < associationRules.size()){
				AssociationRule associationRule = associationRules.get(index);

				return getRuleFeature(hasRuleValues, associationRule, outputField);
			} else

			{
				return null;
			}
		} else

		// Return multiple results
		if("1".equals(isMultiValued)){
			int size;

			int rank = outputField.getRank();
			if(rank < 0){
				throw new InvalidFeatureException(outputField);
			} else

			// "a zero value indicates that all output values are to be returned"
			if(rank == 0){
				size = associationRules.size();
			} else

			// "a positive value indicates the number of output values to be returned"
			{
				size = Math.min(rank, associationRules.size());
			}

			associationRules = associationRules.subList(0, size);

			List<Object> result = Lists.newArrayList();

			for(AssociationRule associationRule : associationRules){
				result.add(getRuleFeature(hasRuleValues, associationRule, outputField));
			}

			return result;
		} else

		{
			throw new InvalidFeatureException(outputField);
		}
	}

	static
	private Object getRuleFeature(HasRuleValues hasRuleValues, AssociationRule associationRule, OutputField outputField){
		RuleFeatureType ruleFeature = outputField.getRuleFeature();

		switch(ruleFeature){
			case ANTECEDENT:
				return getItemValues(hasRuleValues, associationRule.getAntecedent());
			case CONSEQUENT:
				return getItemValues(hasRuleValues, associationRule.getConsequent());
			case RULE:
				{
					Joiner joiner = Joiner.on(',');

					StringBuffer sb = new StringBuffer();

					String left = joiner.join(getItemValues(hasRuleValues, associationRule.getAntecedent()));
					sb.append('{').append(left).append('}');

					sb.append("->");

					String right = joiner.join(getItemValues(hasRuleValues, associationRule.getConsequent()));
					sb.append('{').append(right).append('}');

					return sb.toString();
				}
			case RULE_ID:
				{
					String id = associationRule.getId();
					if(id == null){
						BiMap<String, AssociationRule> associationRuleRegistry = hasRuleValues.getAssociationRuleRegistry();

						id = (associationRuleRegistry.inverse()).get(associationRule);
					}

					return id;
				}
			case CONFIDENCE:
				return associationRule.getConfidence();
			case SUPPORT:
				return associationRule.getSupport();
			case LIFT:
				return associationRule.getLift();
			case LEVERAGE:
				return associationRule.getLeverage();
			case AFFINITY:
				return associationRule.getAffinity();
			default:
				throw new UnsupportedFeatureException(outputField, ruleFeature);
		}
	}

	static
	private List<String> getItemValues(HasRuleValues hasRuleValues, String id){
		List<String> result = Lists.newArrayList();

		BiMap<String, Item> itemRegistry = hasRuleValues.getItemRegistry();
		BiMap<String, Itemset> itemsetRegistry = hasRuleValues.getItemsetRegistry();

		Itemset itemset = itemsetRegistry.get(id);

		List<ItemRef> itemRefs = itemset.getItemRefs();
		for(ItemRef itemRef : itemRefs){
			Item item = itemRegistry.get(itemRef.getItemRef());

			result.add(item.getValue());
		}

		return result;
	}
}