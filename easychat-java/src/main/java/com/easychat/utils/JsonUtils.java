package com.easychat.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class JsonUtils {
    private static final Logger logger = LoggerFactory.getLogger(JsonUtils.class);


    public static JSONWriter.Feature[] FEATURES = new JSONWriter.Feature[]{JSONWriter.Feature.WriteMapNullValue};

    public static String convertObj2Json(Object obj) {
        return JSON.toJSONString(obj, FEATURES);
    }

    public static <T> T convertJson2Obj(String json, Class<T> classz) {
        try {
            return JSONObject.parseObject(json, classz);
        } catch (Exception e) {
            logger.error("convertJson2Obj异常，json:{}", json);
            throw new BusinessException(ResponseCodeEnum.CODE_601);
        }
    }

    public static <T> List<T> convertJsonArray2List(String json, Class<T> classz) {
        try {
            return JSONArray.parseArray(json, classz);
        } catch (Exception e) {
            logger.error("convertJsonArray2List,json:{}", json, e);
            throw new BusinessException(ResponseCodeEnum.CODE_601);
        }
    }
}
