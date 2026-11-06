/**
  ******************************************************************************
  * @file    json_util.h
  * @brief   极简 JSON 取值（够用即可，不引入 cJSON 以省 RAM）
  *   - JSON_GetNestedInt: 解析 "key":{"value":N}（OneNET 属性下行格式）
  *   - JSON_GetDirectInt: 解析 "key":N（OneNET 服务参数格式）
  ******************************************************************************
  */

#ifndef __JSON_UTIL_H
#define __JSON_UTIL_H

#include <stdbool.h>

bool JSON_GetNestedInt(const char *json, const char *key, int *out);
bool JSON_GetDirectInt(const char *json, const char *key, int *out);

#endif /* __JSON_UTIL_H */
