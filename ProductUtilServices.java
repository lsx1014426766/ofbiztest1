/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.product.product;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

import javolution.util.FastList;
import javolution.util.FastMap;
import javolution.util.FastSet;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.StringUtil;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.base.util.string.FlexibleStringExpander;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericPK;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.model.DynamicViewEntity;
import org.ofbiz.entity.model.ModelEntity;
import org.ofbiz.entity.model.ModelKeyMap;
import org.ofbiz.entity.util.EntityFindOptions;
import org.ofbiz.entity.util.EntityListIterator;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

/**
 * Product Services
 */
public class ProductUtilServices {

    public static final String module = ProductUtilServices.class.getName();
    public static final String resource = "ProductUiLabels";
    public static final String resourceError = "ProductErrorUiLabels";

    /** First expire all ProductAssocs for all disc variants, then disc all virtuals that have all expired variant ProductAssocs */
    public static Map<String, Object> discVirtualsWithDiscVariants(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Timestamp nowTimestamp = UtilDateTime.nowTimestamp();
        Locale locale = (Locale) context.get("locale");
        String errMsg = null;

        try {
            EntityCondition conditionOne = EntityCondition.makeCondition(UtilMisc.toList(
                    EntityCondition.makeCondition("isVariant", EntityOperator.EQUALS, "Y"),
                    EntityCondition.makeCondition("salesDiscontinuationDate", EntityOperator.NOT_EQUAL, null),
                    EntityCondition.makeCondition("salesDiscontinuationDate", EntityOperator.LESS_THAN_EQUAL_TO, nowTimestamp)
                   ), EntityOperator.AND);
            EntityListIterator eliOne = delegator.find("Product", conditionOne, null, null, null, null);
            GenericValue productOne = null;
            int numSoFarOne = 0;
            while ((productOne = eliOne.next()) != null) {
                String virtualProductId = ProductWorker.getVariantVirtualId(productOne);
                GenericValue virtualProduct = delegator.findOne("Product", UtilMisc.toMap("productId", virtualProductId), false);
                if (virtualProduct == null) {
                    continue;
                }
                List<GenericValue> passocList = delegator.findByAnd("ProductAssoc", UtilMisc.toMap("productId", virtualProductId, "productIdTo", productOne.get("productId"), "productAssocTypeId", "PRODUCT_VARIANT"), null, false);
                passocList = EntityUtil.filterByDate(passocList);
                if (passocList.size() > 0) {
                    for (GenericValue passoc: passocList) {
                        passoc.set("thruDate", nowTimestamp);
                        passoc.store();
                    }

                    numSoFarOne++;
                    if (numSoFarOne % 500 == 0) {
                        Debug.logInfo("Expired variant ProductAssocs for " + numSoFarOne + " sales discontinued variant products.", module);
                    }
                }
            }
            eliOne.close();

            // get all non-discontinued virtuals, see if all variant ProductAssocs are expired, if discontinue
            EntityCondition condition = EntityCondition.makeCondition(UtilMisc.toList(
                    EntityCondition.makeCondition("isVirtual", EntityOperator.EQUALS, "Y"),
                    EntityCondition.makeCondition(EntityCondition.makeCondition("salesDiscontinuationDate", EntityOperator.EQUALS, null), EntityOperator.OR, EntityCondition.makeCondition("salesDiscontinuationDate", EntityOperator.GREATER_THAN_EQUAL_TO, nowTimestamp))
                   ), EntityOperator.AND);
            EntityListIterator eli = delegator.find("Product", condition, null, null, null, null);
            GenericValue product = null;
            int numSoFar = 0;
            while ((product = eli.next()) != null) {
                List<GenericValue> passocList = delegator.findByAnd("ProductAssoc", UtilMisc.toMap("productId", product.get("productId"), "productAssocTypeId", "PRODUCT_VARIANT"), null, false);
                passocList = EntityUtil.filterByDate(passocList);
                if (passocList.size() == 0) {
                    product.set("salesDiscontinuationDate", nowTimestamp);
                    delegator.store(product);

                    numSoFar++;
                    if (numSoFar % 500 == 0) {
                        Debug.logInfo("Sales discontinued " + numSoFar + " virtual products that have no valid variants.", module);
                    }
                }
            }
            eli.close();
        } catch (GenericEntityException e) {
            Map<String, String> messageMap = UtilMisc.toMap("errMessage", e.toString());
            errMsg = UtilProperties.getMessage(resourceError,"productutilservices.entity_error_running_discVirtualsWithDiscVariants", messageMap, locale);
            Debug.logError(e, errMsg, module);
            return ServiceUtil.returnError(errMsg);
        }

        return ServiceUtil.returnSuccess();
    }

    /** for all disc products, remove from category memberships */
    public static Map<String, Object> removeCategoryMembersOfDiscProducts(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Timestamp nowTimestamp = UtilDateTime.nowTimestamp();
        Locale locale = (Locale) context.get("locale");
        String errMsg = null;

        try {
            EntityCondition condition = EntityCondition.makeCondition(UtilMisc.toList(
                    EntityCondition.makeCondition("salesDiscontinuationDate", EntityOperator.NOT_EQUAL, null),
                    EntityCondition.makeCondition("salesDiscontinuationDate", EntityOperator.LESS_THAN_EQUAL_TO, nowTimestamp)
                   ), EntityOperator.AND);
            EntityListIterator eli = delegator.find("Product", condition, null, null, null, null);
            GenericValue product = null;
            int numSoFar = 0;
            while ((product = eli.next()) != null) {
                String productId = product.getString("productId");
                List<GenericValue> productCategoryMemberList = delegator.findByAnd("ProductCategoryMember", UtilMisc.toMap("productId", productId), null, false);
                if (productCategoryMemberList.size() > 0) {
                    for (GenericValue productCategoryMember: productCategoryMemberList) {
                        // coded this way rather than a removeByAnd so it can be easily changed...
                        productCategoryMember.remove();
                    }
                    numSoFar++;
                    if (numSoFar % 500 == 0) {
                        Debug.logInfo("Removed category members for " + numSoFar + " sales discontinued products.", module);
                    }
                }
            }
            eli.close();
            Debug.logInfo("Completed - Removed category members for " + numSoFar + " sales discontinued products.", module);
        } catch (GenericEntityException e) {
            Map<String, String> messageMap = UtilMisc.toMap("errMessage", e.toString());
            errMsg = UtilProperties.getMessage(resourceError,"productutilservices.entity_error_running_removeCategoryMembersOfDiscProducts", messageMap, locale);
            Debug.logError(e, errMsg, module);
            return ServiceUtil.returnError(errMsg);
        }

        return ServiceUtil.returnSuccess();
    }

    public static Map<String, Object> removeDuplicateOpenEndedCategoryMembers(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Timestamp nowTimestamp = UtilDateTime.nowTimestamp();
        Locale locale = (Locale) context.get("locale");
        String errMsg = null;

        try {
            DynamicViewEntity dve = new DynamicViewEntity();
            dve.addMemberEntity("PCM", "ProductCategoryMember");
            dve.addAlias("PCM", "productId", null, null, null, Boolean.TRUE, null);
            dve.addAlias("PCM", "productCategoryId", null, null, null, Boolean.TRUE, null);
            dve.addAlias("PCM", "fromDate", null, null, null, null, null);
            dve.addAlias("PCM", "thruDate", null, null, null, null, null);
            dve.addAlias("PCM", "productIdCount", "productId", null, null, null, "count");

            EntityCondition condition = EntityCondition.makeCondition(UtilMisc.toList(
                    EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN, nowTimestamp),
                    EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null)
                   ), EntityOperator.AND);
            EntityCondition havingCond = EntityCondition.makeCondition("productIdCount", EntityOperator.GREATER_THAN, Long.valueOf(1));
            EntityListIterator eli = delegator.findListIteratorByCondition(dve, condition, havingCond, UtilMisc.toList("productId", "productCategoryId", "productIdCount"), null, null);
            GenericValue pcm = null;
            int numSoFar = 0;
            while ((pcm = eli.next()) != null) {
                List<GenericValue> productCategoryMemberList = delegator.findByAnd("ProductCategoryMember", UtilMisc.toMap("productId", pcm.get("productId"), "productCategoryId", pcm.get("productCategoryId")), null, false);
                if (productCategoryMemberList.size() > 1) {
                    // remove all except the first...
                    productCategoryMemberList.remove(0);
                    for (GenericValue productCategoryMember: productCategoryMemberList) {
                        productCategoryMember.remove();
                    }
                    numSoFar++;
                    if (numSoFar % 500 == 0) {
                        Debug.logInfo("Removed category members for " + numSoFar + " products with duplicate category members.", module);
                    }
                }
            }
            eli.close();
            Debug.logInfo("Completed - Removed category members for " + numSoFar + " products with duplicate category members.", module);
        } catch (GenericEntityException e) {
            Map<String, String> messageMap = UtilMisc.toMap("errMessage", e.toString());
            errMsg = UtilProperties.getMessage(resourceError,"productutilservices.entity_error_running_removeDuplicateOpenEndedCategoryMembers", messageMap, locale);
            Debug.logError(e, errMsg, module);
            return ServiceUtil.returnError(errMsg);
        }

        return ServiceUtil.returnSuccess();
    }

    public static Map<String, Object> makeStandAloneFromSingleVariantVirtuals(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp nowTimestamp = UtilDateTime.nowTimestamp();
        Locale locale = (Locale) context.get("locale");
        String errMsg = null;

        Debug.logInfo("Starting makeStandAloneFromSingleVariantVirtuals", module);

        DynamicViewEntity dve = new DynamicViewEntity();
        dve.addMemberEntity("PVIRT", "Product");
        dve.addMemberEntity("PVA", "ProductAssoc");
        //dve.addMemberEntity("PVAR", "Product");
        dve.addViewLink("PVIRT", "PVA", Boolean.FALSE, UtilMisc.toList(new ModelKeyMap("productId", "productId")));
        //dve.addViewLink("PVA", "PVAR", Boolean.FALSE, UtilMisc.toList(new ModelKeyMap("productIdTo", "productId")));
        dve.addAlias("PVIRT", "productId", null, null, null, Boolean.TRUE, null);
        dve.addAlias("PVIRT", "salesDiscontinuationDate", null, null, null, null, null);
        dve.addAlias("PVA", "productAssocTypeId", null, null, null, null, null);
        dve.addAlias("PVA", "fromDate", null, null, null, null, null);
        dve.addAlias("PVA", "thruDate", null, null, null, null, null);
        dve.addAlias("PVA", "productIdToCount", "productIdTo", null, null, null, "count-distinct");
        //dve.addAlias("PVAR", "variantProductId", "productId", null, null, null, null);

        try {
            EntityCondition condition = EntityCondition.makeCondition(UtilMisc.toList(
                    EntityCondition.makeCondition("productAssocTypeId", EntityOperator.EQUALS, "PRODUCT_VARIANT"),
                    EntityCondition.makeCondition(EntityCondition.makeCondition("salesDiscontinuationDate", EntityOperator.EQUALS, null), EntityOperator.OR, EntityCondition.makeCondition("salesDiscontinuationDate", EntityOperator.GREATER_THAN, nowTimestamp))
                   ), EntityOperator.AND);
            EntityCondition havingCond = EntityCondition.makeCondition("productIdToCount", EntityOperator.EQUALS, Long.valueOf(1));
            EntityListIterator eliOne = delegator.findListIteratorByCondition(dve, condition, havingCond, UtilMisc.toList("productId", "productIdToCount"), null, null);
            List<GenericValue> valueList = eliOne.getCompleteList();
            eliOne.close();

            Debug.logInfo("Found " + valueList.size() + " virtual products with one variant to turn into a stand alone product.", module);

            int numWithOneOnly = 0;
            for (GenericValue value: valueList) {
                // has only one variant period, is it valid? should already be discontinued if not
                String productId = value.getString("productId");
                List<GenericValue> paList = delegator.findByAnd("ProductAssoc", UtilMisc.toMap("productId", productId, "productAssocTypeId", "PRODUCT_VARIANT"), null, false);
                paList = EntityUtil.filterByDate(paList);
                // verify the query; tested on a bunch, looks good
                if (paList.size() != 1) {
                    Debug.logInfo("Virtual product with ID " + productId + " should have 1 assoc, has " + paList.size(), module);
                } else {
                    //if (numWithOneOnly < 100) {
                    //    Debug.logInfo("Virtual product ID to make stand-alone: " + productId, module);
                    //}
                    // for all virtuals with one variant move all info from virtual to variant and remove virtual, make variant as not a variant
                    dispatcher.runSync("mergeVirtualWithSingleVariant", UtilMisc.<String, Object>toMap("productId", productId, "removeOld", Boolean.TRUE, "userLogin", userLogin));

                    numWithOneOnly++;
                    if (numWithOneOnly % 100 == 0) {
                        Debug.logInfo("Made " + numWithOneOnly + " virtual products with only one valid variant stand-alone products.", module);
                    }
                }
            }

            EntityCondition conditionWithDates = EntityCondition.makeCondition(UtilMisc.toList(
                    EntityCondition.makeCondition("productAssocTypeId", EntityOperator.EQUALS, "PRODUCT_VARIANT"),
                    EntityCondition.makeCondition(EntityCondition.makeCondition("salesDiscontinuationDate", EntityOperator.EQUALS, null), EntityOperator.OR, EntityCondition.makeCondition("salesDiscontinuationDate", EntityOperator.GREATER_THAN, nowTimestamp)),
                    EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, nowTimestamp),
                    EntityCondition.makeCondition(EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null), EntityOperator.OR, EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, nowTimestamp))
                   ), EntityOperator.AND);
            EntityListIterator eliMulti = delegator.findListIteratorByCondition(dve, conditionWithDates, havingCond, UtilMisc.toList("productId", "productIdToCount"), null, null);
            List<GenericValue> valueMultiList = eliMulti.getCompleteList();
            eliMulti.close();

            Debug.logInfo("Found " + valueMultiList.size() + " virtual products with one VALID variant to pull the variant from to make a stand alone product.", module);

            int numWithOneValid = 0;
            for (GenericValue value: valueMultiList) {
                // has only one valid variant
                String productId = value.getString("productId");

                List<GenericValue> paList = EntityUtil.filterByDate(delegator.findByAnd("ProductAssoc", UtilMisc.toMap("productId", productId, "productAssocTypeId", "PRODUCT_VARIANT"), null, false));

                // verify the query; tested on a bunch, looks good
                if (paList.size() != 1) {
                    Debug.logInfo("Virtual product with ID " + productId + " should have 1 assoc, has " + paList.size(), module);
                } else {
                    // for all virtuals with one valid variant move info from virtual to variant, put variant in categories from virtual, remove virtual from all categories but leave "family" otherwise intact, mark variant as not a variant
                    dispatcher.runSync("mergeVirtualWithSingleVariant", UtilMisc.<String, Object>toMap("productId", productId, "removeOld", Boolean.FALSE, "userLogin", userLogin));

                    numWithOneValid++;
                    if (numWithOneValid % 100 == 0) {
                        Debug.logInfo("Made " + numWithOneValid + " virtual products with one valid variant stand-alone products.", module);
                    }
                }
            }

            Debug.logInfo("Found virtual products with one valid variant: " + numWithOneValid + ", with one variant only: " + numWithOneOnly, module);
        } catch (GenericEntityException e) {
            Map<String, String> messageMap = UtilMisc.toMap("errMessage", e.toString());
            errMsg = UtilProperties.getMessage(resourceError,"productutilservices.entity_error_running_makeStandAloneFromSingleVariantVirtuals", messageMap, locale);
            Debug.logError(e, errMsg, module);
            return ServiceUtil.returnError(errMsg);
        } catch (GenericServiceException e) {
            Map<String, String> messageMap = UtilMisc.toMap("errMessage", e.toString());
            errMsg = UtilProperties.getMessage(resourceError,"productutilservices.entity_error_running_makeStandAloneFromSingleVariantVirtuals", messageMap, locale);
            Debug.logError(e, errMsg, module);
            return ServiceUtil.returnError(errMsg);
        }

        return ServiceUtil.returnSuccess();
    }

    public static Map<String, Object> mergeVirtualWithSingleVariant(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Timestamp nowTimestamp = UtilDateTime.nowTimestamp();

        String productId = (String) context.get("productId");
        Boolean removeOldBool = (Boolean) context.get("removeOld");
        boolean removeOld = removeOldBool.booleanValue();
        Locale locale = (Locale) context.get("locale");
        String errMsg = null;

        Boolean testBool = (Boolean) context.get("test");
        boolean test = false;
        if (testBool != null) {
            test = testBool.booleanValue();
        }

        try {
            GenericValue product = delegator.findOne("Product", UtilMisc.toMap("productId", productId), false);
            Debug.logInfo("Processing virtual product with one variant with ID: " + productId + " and name: " + product.getString("internalName"), module);

            List<GenericValue> paList = EntityUtil.filterByDate(delegator.findByAnd("ProductAssoc", UtilMisc.toMap("productId", productId, "productAssocTypeId", "PRODUCT_VARIANT"), null, false));
            if (paList.size() > 1) {
                Map<String, String> messageMap = UtilMisc.toMap("productId", productId);
                errMsg = UtilProperties.getMessage(resourceError,"productutilservices.found_more_than_one_valid_variant_for_virtual_ID", messageMap, locale);
                Debug.logInfo(errMsg, module);
                return ServiceUtil.returnError(errMsg);
            }

            if (paList.size() == 0) {
                Map<String, String> messageMap = UtilMisc.toMap("productId", productId);
                errMsg = UtilProperties.getMessage(resourceError,"productutilservices.did_not_find_any_valid_variants_for_virtual_ID", messageMap, locale);
                Debug.logInfo(errMsg, module);
                return ServiceUtil.returnError(errMsg);
            }

            GenericValue productAssoc = EntityUtil.getFirst(paList);
            if (removeOld) {
                // remove the productAssoc before getting down so it isn't copied over...
                if (test) {
                    Debug.logInfo("Test mode, would remove: " + productAssoc, module);
                } else {
                    productAssoc.remove();
                }
            } else {
                // don't remove, just expire to avoid running again in the future
                productAssoc.set("thruDate", nowTimestamp);
                if (test) {
                    Debug.logInfo("Test mode, would store: " + productAssoc, module);
                } else {
                    productAssoc.store();
                }
            }
            String variantProductId = productAssoc.getString("productIdTo");

            // Product
            GenericValue variantProduct = delegator.findOne("Product", UtilMisc.toMap("productId", variantProductId), false);

            Debug.logInfo("--variant has ID: " + variantProductId + " and name: " + variantProduct.getString("internalName"), module);

            // start with the values from the virtual product, override from the variant...
            GenericValue newVariantProduct = delegator.makeValue("Product", product);
            newVariantProduct.setAllFields(variantProduct, false, "", null);
            newVariantProduct.set("isVariant", "N");
            if (test) {
                Debug.logInfo("Test mode, would store: " + newVariantProduct, module);
            } else {
                newVariantProduct.store();
            }

            // ProductCategoryMember - always remove these to pull the virtual from any categories it might have been in
            duplicateRelated(product, "", "ProductCategoryMember", "productId", variantProductId, nowTimestamp, true, delegator, test);

            // ProductFeatureAppl
            duplicateRelated(product, "", "ProductFeatureAppl", "productId", variantProductId, nowTimestamp, removeOld, delegator, test);

            // ProductContent
            duplicateRelated(product, "", "ProductContent", "productId", variantProductId, nowTimestamp, removeOld, delegator, test);

            // ProductPrice
            duplicateRelated(product, "", "ProductPrice", "productId", variantProductId, nowTimestamp, removeOld, delegator, test);

            // GoodIdentification
            duplicateRelated(product, "", "GoodIdentification", "productId", variantProductId, nowTimestamp, removeOld, delegator, test);

            // ProductAttribute
            duplicateRelated(product, "", "ProductAttribute", "productId", variantProductId, nowTimestamp, removeOld, delegator, test);

            // ProductAssoc
            duplicateRelated(product, "Main", "ProductAssoc", "productId", variantProductId, nowTimestamp, removeOld, delegator, test);
            duplicateRelated(product, "Assoc", "ProductAssoc", "productIdTo", variantProductId, nowTimestamp, removeOld, delegator, test);

            if (removeOld) {
                if (test) {
                    Debug.logInfo("Test mode, would remove related ProductKeyword with dummy key: " + product.getRelatedDummyPK("ProductKeyword"), module);
                    Debug.logInfo("Test mode, would remove: " + product, module);
                } else {
                    product.removeRelated("ProductKeyword");
                    product.remove();
                }
            }

            if (test) {
                return ServiceUtil.returnError(UtilProperties.getMessage(resource, 
                        "ProductMergeVirtualWithSingleVariant", locale));
            }
        } catch (GenericEntityException e) {
            Map<String, String> messageMap = UtilMisc.toMap("errMessage", e.toString());
            errMsg = UtilProperties.getMessage(resourceError,"productutilservices.entity_error_running_makeStandAloneFromSingleVariantVirtuals", messageMap, locale);
            Debug.logError(e, errMsg, module);
            return ServiceUtil.returnError(errMsg);
        }

        return ServiceUtil.returnSuccess();
    }

    protected static void duplicateRelated(GenericValue product, String title, String relatedEntityName, String productIdField, String variantProductId, Timestamp nowTimestamp, boolean removeOld, Delegator delegator, boolean test) throws GenericEntityException {
        List<GenericValue> relatedList = EntityUtil.filterByDate(product.getRelated(title + relatedEntityName, null, null, false), nowTimestamp);
        for (GenericValue relatedValue: relatedList) {
            GenericValue newRelatedValue = (GenericValue) relatedValue.clone();
            newRelatedValue.set(productIdField, variantProductId);

            // create a new one? see if one already exists with different from/thru dates
            ModelEntity modelEntity = relatedValue.getModelEntity();
            if (modelEntity.isField("fromDate")) {
                GenericPK findValue = newRelatedValue.getPrimaryKey();
                // can't just set to null, need to remove the value so it isn't a constraint in the query
                //findValue.set("fromDate", null);
                findValue.remove("fromDate");
                List<GenericValue> existingValueList = EntityUtil.filterByDate(delegator.findByAnd(relatedEntityName, findValue, null, false), nowTimestamp);
                if (existingValueList.size() > 0) {
                    if (test) {
                        Debug.logInfo("Found " + existingValueList.size() + " existing values for related entity name: " + relatedEntityName + ", not copying, findValue is: " + findValue, module);
                    }
                    continue;
                }
                newRelatedValue.set("fromDate", nowTimestamp);
            }

            if (delegator.findCountByCondition(relatedEntityName, EntityCondition.makeCondition(newRelatedValue.getPrimaryKey(), EntityOperator.AND), null, null) == 0) {
                if (test) {
                    Debug.logInfo("Test mode, would create: " + newRelatedValue, module);
                } else {
                    newRelatedValue.create();
                }
            }
        }
        if (removeOld) {
            if (test) {
                Debug.logInfo("Test mode, would remove related " + title + relatedEntityName + " with dummy key: " + product.getRelatedDummyPK(title + relatedEntityName), module);
            } else {
                product.removeRelated(title + relatedEntityName);
            }
        }
    }


    /** reset all product image names with a certain pattern, ex: /images/products/${size}/${productId}.jpg
     * NOTE: only works on fields of Product right now
     */
    public static Map<String, Object> setAllProductImageNames(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String pattern = (String) context.get("pattern");
        Locale locale = (Locale) context.get("locale");
        String errMsg = null;

        if (UtilValidate.isEmpty(pattern)) {
            Map<String, Object>imageContext = FastMap.newInstance();
            imageContext.putAll(context);
            imageContext.put("tenantId",delegator.getDelegatorTenantId());
            String imageFilenameFormat = EntityUtilProperties.getPropertyValue("catalog", "image.filename.format", delegator);
            String imageUrlPrefix = FlexibleStringExpander.expandString(EntityUtilProperties.getPropertyValue("catalog", "image.url.prefix",delegator), imageContext);
            imageUrlPrefix = imageUrlPrefix.endsWith("/") ? imageUrlPrefix.substring(0, imageUrlPrefix.length()-1) : imageUrlPrefix;
            pattern = imageUrlPrefix + "/" + imageFilenameFormat;
        }

        try {
            EntityListIterator eli = delegator.find("Product", null, null, null, null, null);
            GenericValue product = null;
            int numSoFar = 0;
            while ((product = eli.next()) != null) {
                String productId = (String) product.get("productId");
                Map<String, String> smallMap = UtilMisc.toMap("size", "small", "productId", productId);
                Map<String, String> mediumMap = UtilMisc.toMap("size", "medium", "productId", productId);
                Map<String, String> largeMap = UtilMisc.toMap("size", "large", "productId", productId);
                Map<String, String> detailMap = UtilMisc.toMap("size", "detail", "productId", productId);

                if ("Y".equals(product.getString("isVirtual"))) {
                    // find the first variant, use it's ID for the names...
                    List<GenericValue> productAssocList = EntityUtil.filterByDate(delegator.findByAnd("ProductAssoc", UtilMisc.toMap("productId", productId, "productAssocTypeId", "PRODUCT_VARIANT"), null, false));
                    if (productAssocList.size() > 0) {
                        GenericValue productAssoc = EntityUtil.getFirst(productAssocList);
                        smallMap.put("productId", productAssoc.getString("productIdTo"));
                        mediumMap.put("productId", productAssoc.getString("productIdTo"));
                        product.set("smallImageUrl", FlexibleStringExpander.expandString(pattern, smallMap));
                        product.set("mediumImageUrl", FlexibleStringExpander.expandString(pattern, mediumMap));
                    } else {
                        product.set("smallImageUrl", null);
                        product.set("mediumImageUrl", null);
                    }
                    product.set("largeImageUrl", null);
                    product.set("detailImageUrl", null);
                } else {
                    product.set("smallImageUrl", FlexibleStringExpander.expandString(pattern, smallMap));
                    product.set("mediumImageUrl", FlexibleStringExpander.expandString(pattern, mediumMap));
                    product.set("largeImageUrl", FlexibleStringExpander.expandString(pattern, largeMap));
                    product.set("detailImageUrl", FlexibleStringExpander.expandString(pattern, detailMap));
                }

                product.store();
                numSoFar++;
                if (numSoFar % 500 == 0) {
                    Debug.logInfo("Image URLs set for " + numSoFar + " products.", module);
                }
            }
            eli.close();
            Debug.logInfo("Completed - Image URLs set for " + numSoFar + " products.", module);
        } catch (GenericEntityException e) {
            Map<String, String> messageMap = UtilMisc.toMap("errMessage", e.toString());
            errMsg = UtilProperties.getMessage(resourceError,"productutilservices.entity_error_running_setAllProductImageNames", messageMap, locale);
            Debug.logError(e, errMsg, module);
            return ServiceUtil.returnError(errMsg);
        }

        return ServiceUtil.returnSuccess();
    }

    public static Map<String, Object> clearAllVirtualProductImageNames(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String errMsg = null;

        try {
            EntityListIterator eli = delegator.find("Product", EntityCondition.makeCondition("isVirtual", EntityOperator.EQUALS, "Y"), null, null, null, null);
            GenericValue product = null;
            int numSoFar = 0;
            while ((product = eli.next()) != null) {
                product.set("smallImageUrl", null);
                product.set("mediumImageUrl", null);
                product.set("largeImageUrl", null);
                product.set("detailImageUrl", null);
                product.store();
                numSoFar++;
                if (numSoFar % 500 == 0) {
                    Debug.logInfo("Image URLs cleared for " + numSoFar + " products.", module);
                }
            }
            eli.close();
            Debug.logInfo("Completed - Image URLs set for " + numSoFar + " products.", module);
        } catch (GenericEntityException e) {
            Map<String, String> messageMap = UtilMisc.toMap("errMessage", e.toString());
            errMsg = UtilProperties.getMessage(resourceError,"productutilservices.entity_error_running_clearAllVirtualProductImageNames", messageMap, locale);
            Debug.logError(e, errMsg, module);
            return ServiceUtil.returnError(errMsg);
        }

        return ServiceUtil.returnSuccess();
    }

    // set category descriptions from longDescriptions
    /*
allCategories = delegator.findList("ProductCategory", null, null, null, null, false);
allCatIter = allCategories.iterator();
while (allCatIter.hasNext()) {
   cat = allCatIter.next();
   if (UtilValidate.isEmpty(cat.getString("description"))) {
       StringBuilder description = new StringBuilder(cat.getString("longDescription").toLowerCase());
       description.setCharAt(0, Character.toUpperCase(description.charAt(0)));
       for (int i=0; i<description.length() - 1; i++) {
           if (description.charAt(i) == ' ') {
               description.setCharAt(i+1, Character.toUpperCase(description.charAt(i+1)));
           }
       }
       Debug.logInfo("new description: " + description, "ctc.bsh");
              cat.put("description", description.toString());
       cat.store();
   }
}
     */



    public static Map<String, Object> attachProductFeaturesToCategory(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String productCategoryId = (String) context.get("productCategoryId");
        String doSubCategoriesStr = (String) context.get("doSubCategories");
        Locale locale = (Locale) context.get("locale");
        String errMsg = null;

        // default to true
        boolean doSubCategories = !"N".equals(doSubCategoriesStr);
        Timestamp nowTimestamp = UtilDateTime.nowTimestamp();

        Set<String> productFeatureTypeIdsToExclude = FastSet.newInstance();
        String excludeProp = UtilProperties.getPropertyValue("prodsearch", "attach.feature.type.exclude");
        if (UtilValidate.isNotEmpty(excludeProp)) {
            List<String> typeList = StringUtil.split(excludeProp, ",");
            productFeatureTypeIdsToExclude.addAll(typeList);
        }

        Set<String> productFeatureTypeIdsToInclude = null;
        String includeProp = UtilProperties.getPropertyValue("prodsearch", "attach.feature.type.include");
        if (UtilValidate.isNotEmpty(includeProp)) {
            List<String> typeList = StringUtil.split(includeProp, ",");
            if (typeList.size() > 0) {
                productFeatureTypeIdsToInclude = UtilMisc.makeSetWritable(typeList);
            }
        }

        try {
            attachProductFeaturesToCategory(productCategoryId, productFeatureTypeIdsToInclude, productFeatureTypeIdsToExclude, delegator, doSubCategories, nowTimestamp);
        } catch (GenericEntityException e) {
            Map<String, String> messageMap = UtilMisc.toMap("errMessage", e.toString());
            errMsg = UtilProperties.getMessage(resourceError,"productutilservices.error_in_attachProductFeaturesToCategory", messageMap, locale);
            Debug.logError(e, errMsg, module);
            return ServiceUtil.returnError(errMsg);
        }

        return ServiceUtil.returnSuccess();
    }

    /** Get all features associated with products and associate them with a feature group attached to the category for each feature type;
     * includes products associated with this category only, but will also associate all feature groups of sub-categories with this category, optionally calls this method for all sub-categories too
     */
    public static void attachProductFeaturesToCategory(String productCategoryId, Set<String> productFeatureTypeIdsToInclude, Set<String> productFeatureTypeIdsToExclude, Delegator delegator, boolean doSubCategories, Timestamp nowTimestamp) throws GenericEntityException {
        if (nowTimestamp == null) {
            nowTimestamp = UtilDateTime.nowTimestamp();
        }

        // do sub-categories first so all feature groups will be in place
        //查找这个分类的所有的直接子分类
        List<GenericValue> subCategoryList = delegator.findByAnd("ProductCategoryRollup", UtilMisc.toMap("parentProductCategoryId", productCategoryId), null, false);
        //默认查找子分类下的产品的特征，true
        if (doSubCategories) {
        	//1暂时假设无直接子分类，则跳过递归subCategoryList[]
        	//2（多数）递归查找，直到当前分类没有子分类
            for (GenericValue productCategoryRollup: subCategoryList) {
                attachProductFeaturesToCategory(productCategoryRollup.getString("productCategoryId"), productFeatureTypeIdsToInclude, productFeatureTypeIdsToExclude, delegator, true, nowTimestamp);
            }
        }

        // now get all features for this category and make associated feature groups
        Map<String, Set<String>> productFeatureIdByTypeIdSetMap = FastMap.newInstance();
        //获取当前分类下的产品
        List<GenericValue> productCategoryMemberList = delegator.findByAnd("ProductCategoryMember", UtilMisc.toMap("productCategoryId", productCategoryId), null, false);
        for (GenericValue productCategoryMember: productCategoryMemberList) {
            //条件 prouductid,fromdate>now,thrudate>now||truedate is null
        	String productId = productCategoryMember.getString("productId");
            EntityCondition condition = EntityCondition.makeCondition(UtilMisc.toList(
                    EntityCondition.makeCondition("productId", EntityOperator.EQUALS, productId),
                    EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, nowTimestamp),
                    EntityCondition.makeCondition(EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null), EntityOperator.OR, EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, nowTimestamp))
           ), EntityOperator.AND);
          //查找产品和特征的关系表
            EntityListIterator productFeatureAndApplEli = delegator.find("ProductFeatureAndAppl", condition, null, null, null, null);
            GenericValue productFeatureAndAppl = null;
            //遍历此产品上的特征
            while ((productFeatureAndAppl = productFeatureAndApplEli.next()) != null) {
                String productFeatureId = productFeatureAndAppl.getString("productFeatureId");
                String productFeatureTypeId = productFeatureAndAppl.getString("productFeatureTypeId");
                //特征类型不符合的直接跳过
                if (UtilValidate.isNotEmpty(productFeatureTypeIdsToInclude) && !productFeatureTypeIdsToInclude.contains(productFeatureTypeId)) {
                    continue;
                }
                if (productFeatureTypeIdsToExclude != null && productFeatureTypeIdsToExclude.contains(productFeatureTypeId)) {
                    continue;
                }
                //对特征通过type类型归类
                Set<String> productFeatureIdSet = productFeatureIdByTypeIdSetMap.get(productFeatureTypeId);
                if (productFeatureIdSet == null) {
                	//若还没有出现过这个type的，就建立set对象用来存放，key为type
                    productFeatureIdSet = FastSet.newInstance();
                    productFeatureIdByTypeIdSetMap.put(productFeatureTypeId, productFeatureIdSet);
                }
                //否则的话，直接把这个值放入set中,map可以识别！！
                productFeatureIdSet.add(productFeatureId);
            }
            productFeatureAndApplEli.close();
        }
        //遍历刚才生成的map，生成特征组
        for (Map.Entry<String, Set<String>> entry: productFeatureIdByTypeIdSetMap.entrySet()) {
            String productFeatureTypeId = entry.getKey();
            Set<String> productFeatureIdSet = entry.getValue();
            //特征组的命名：分类id_类型id
            String productFeatureGroupId = productCategoryId + "_" + productFeatureTypeId;
            if (productFeatureGroupId.length() > 20) {
            	//这里超过20就截取，这样会导致又可能出现手动生成的特征组重名，尤其是当一个分类下又多个特征类型，而特征类型的前几个字母相同时。。
                Debug.logWarning("Manufactured productFeatureGroupId was greater than 20 characters, means that we had some long productCategoryId and/or productFeatureTypeId values, at the category part should be unique since it is first, so if the feature type isn't unique it just means more than one type of feature will go into the category...", module);
                productFeatureGroupId = productFeatureGroupId.substring(0, 20);
            }
            //首先查找上面生成的特征组id是否已经存在
            GenericValue productFeatureGroup = delegator.findOne("ProductFeatureGroup", UtilMisc.toMap("productFeatureGroupId", productFeatureGroupId), false);
            //不存在可以安全插入
            if (productFeatureGroup == null) {
                // auto-create the group
            	//生成特征组
                String description = "Feature Group for type [" + productFeatureTypeId + "] features in category [" + productCategoryId + "]";
                productFeatureGroup = delegator.makeValue("ProductFeatureGroup", UtilMisc.toMap("productFeatureGroupId", productFeatureGroupId, "description", description));
                productFeatureGroup.create();
                //生成特征组和当前分类的关系数据
                GenericValue productFeatureCatGrpAppl = delegator.makeValue("ProductFeatureCatGrpAppl", UtilMisc.toMap("productFeatureGroupId", productFeatureGroupId, "productCategoryId", productCategoryId, "fromDate", nowTimestamp));
                productFeatureCatGrpAppl.create();
            }else{
            	//lsx:改动3
            	//特征组存在，但是特征组和当前的分类关系可能通过令外接口被删除了，所以此时还应检查关系是否还在，如果不在了，就重新添加上
            	//delegator.findCountByCondition("ProductFeatureCatGrpAppl", whereEntityCondition, havingEntityCondition, findOptions)
				List<GenericValue> gvs = delegator.findByAnd("ProductFeatureCatGrpAppl", UtilMisc.toMap("productFeatureGroupId", productFeatureGroupId, "productCategoryId", productCategoryId),null,false);
				if(UtilValidate.isEmpty(gvs)){
					GenericValue productFeatureCatGrpAppl = delegator.makeValue("ProductFeatureCatGrpAppl", UtilMisc.toMap("productFeatureGroupId", productFeatureGroupId, "productCategoryId", productCategoryId, "fromDate", nowTimestamp));
	                productFeatureCatGrpAppl.create();
				}
            }
             //不论是之前已有这个特征组和关系数据，下面要做的就是操作这个特征组的特征
            // now put all of the features in the group, if there is not already a valid feature placement there...
            //遍历刚才从当前分类下的产品那里获取到的所有特征
            for (String productFeatureId: productFeatureIdSet) {
            	//查找这个特征和当前的特征组之间是否已经建立关系
                EntityCondition condition = EntityCondition.makeCondition(UtilMisc.toList(
                        EntityCondition.makeCondition("productFeatureId", EntityOperator.EQUALS, productFeatureId),
                        EntityCondition.makeCondition("productFeatureGroupId", EntityOperator.EQUALS, productFeatureGroupId),
                        EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, nowTimestamp),
                        EntityCondition.makeCondition(EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null), EntityOperator.OR, EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, nowTimestamp))
               ), EntityOperator.AND);
                //没有就插入
                if (delegator.findCountByCondition("ProductFeatureGroupAppl", condition, null, null) == 0) {
                    // if no valid ones, create one
                    GenericValue productFeatureGroupAppl = delegator.makeValue("ProductFeatureGroupAppl", UtilMisc.toMap("productFeatureGroupId", productFeatureGroupId, "productFeatureId", productFeatureId, "fromDate", nowTimestamp));
                    productFeatureGroupAppl.create();
                }
                //这里有漏洞：如果之前没有的特征可以加入，但是如果现在的情况是，之前特征组的特征多于现有的实际的，就需要删除，我的建议是最后做一次删除
            }
            //lsx:改动2
            //在遍历添加之前没有的特征后，做一次删除已经不应再存在于该特征组的特征，因为本身这个特征组就是由产品上的特征而来，若产品上都没有了， 那这个特征组中也不该有了
            EntityCondition delcondition = EntityCondition.makeCondition(UtilMisc.toList(
                    EntityCondition.makeCondition("productFeatureId", EntityOperator.NOT_IN,productFeatureIdSet),
                    EntityCondition.makeCondition("productFeatureGroupId", EntityOperator.EQUALS, productFeatureGroupId),
                    EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, nowTimestamp),
                    EntityCondition.makeCondition(EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null), EntityOperator.OR, EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, nowTimestamp))
           ), EntityOperator.AND);
            delegator.removeByCondition("ProductFeatureGroupAppl", delcondition);
          //这个还只是考虑某个特征组中少了特征
        }
        //上面遍历的时候找到了产品的所有type，现在又有当前分类，最简单的做法是还保留这个特征组，而且保留这个特征组和多级分类的关系，而只是找到这个特征组并且清空它里面的内容 
      //更近一步，如果产品连这个特征组都不存在了，这时就需要把特征组和这个分类的关系去掉，这个关系要去掉，首先要找到这个特征组是针对哪个具体分类而建的
        //lsx改动3
        Set<String> types = productFeatureIdByTypeIdSetMap.keySet();
        //此分类下产品特征 ，处理某一类型的特征组在这次操作中“不存在”的情况，这种操作只是针对当前分类下有产品有特征的情况
        if(UtilValidate.isNotEmpty(types)){
        	//直接查找特征组，有指定特征的
            List<EntityCondition> groupcons = FastList.newInstance();
            //String pcidlike=(productCategoryId+"_").substring(0,20);
            groupcons.add(EntityCondition.makeCondition("productFeatureGroupId", EntityOperator.LIKE,"%"+productCategoryId+"_%"));
            groupcons.add(EntityCondition.makeCondition("productFeatureTypeId",EntityOperator.NOT_IN, types));
            EntityFindOptions options = new EntityFindOptions();
            options.setDistinct(true);
            List<GenericValue> groups = delegator.findList("ProductFeatureGroupAndAppl", EntityCondition.makeCondition(groupcons), UtilMisc.toSet("productFeatureGroupId"),null,options,false);
            if(UtilValidate.isNotEmpty(groups)){
            	//若存在这样的
            	for(GenericValue g:groups){
            		//例如：groupid=cid_color
            		String groupid = g.getString("productFeatureGroupId");
            		//移除这个分类组下的特征
            		delegator.removeByAnd("ProductFeatureGroupAppl", UtilMisc.toMap("productFeatureGroupId", groupid));
            		//移除这个特征组和所有分类的关系，注意不仅仅限于当前分类的
            		//这里保留这个特征组，只是清除了它的所有上下级关系
            		delegator.removeByAnd("ProductFeatureCatGrpAppl", UtilMisc.toMap("productFeatureGroupId", groupid));
            	}
            }
        }
        
        //这里是将当前分类的子分类的特征组和当前分类建立关系
        // now get all feature groups associated with sub-categories and associate them with this category
        for (GenericValue productCategoryRollup: subCategoryList) {
            String subProductCategoryId = productCategoryRollup.getString("productCategoryId");
            EntityCondition condition = EntityCondition.makeCondition(UtilMisc.toList(
                    EntityCondition.makeCondition("productCategoryId", EntityOperator.EQUALS, subProductCategoryId),
                    EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, nowTimestamp),
                    EntityCondition.makeCondition(EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null), EntityOperator.OR, EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, nowTimestamp))
           ), EntityOperator.AND);
            //找到这个子分类相关的特征组，所以要想把子分类的特征组拿过来，子分类和它的特征组之间一定要先建立关系，这里是间接获取的！！！
            EntityListIterator productFeatureCatGrpApplEli = delegator.find("ProductFeatureCatGrpAppl", condition, null, null, null, null);
            GenericValue productFeatureCatGrpAppl = null;
            while ((productFeatureCatGrpAppl = productFeatureCatGrpApplEli.next()) != null) {
                String productFeatureGroupId = productFeatureCatGrpAppl.getString("productFeatureGroupId");
                EntityCondition checkCondition = EntityCondition.makeCondition(UtilMisc.toList(
                        EntityCondition.makeCondition("productCategoryId", EntityOperator.EQUALS, productCategoryId),
                        EntityCondition.makeCondition("productFeatureGroupId", EntityOperator.EQUALS, productFeatureGroupId),
                        EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, nowTimestamp),
                        EntityCondition.makeCondition(EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null), EntityOperator.OR, EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, nowTimestamp))
               ), EntityOperator.AND);
                //查看当前分类和子分类的特征组之间是否建立了关系，没有就创建关系
                if (delegator.findCountByCondition("ProductFeatureCatGrpAppl", checkCondition, null, null) == 0) {
                    // if no valid ones, create one
                    GenericValue productFeatureGroupAppl = delegator.makeValue("ProductFeatureCatGrpAppl", UtilMisc.toMap("productFeatureGroupId", productFeatureGroupId, "productCategoryId", productCategoryId, "fromDate", nowTimestamp));
                    productFeatureGroupAppl.create();
                }
            }
            productFeatureCatGrpApplEli.close();
        }
    }

    public static Map<String, Object> removeAllFeatureGroupsForCategory(DispatchContext dctx, Map<String, ? extends Object> context) {
        return ServiceUtil.returnSuccess();
    }

    public static void getFeatureGroupsForCategory(String productCategoryId, Set<String> productFeatureGroupIdsToRemove, Delegator delegator, boolean doSubCategories, Timestamp nowTimestamp) throws GenericEntityException {

    }
}

