/**
  ******************************************************************************
  * @file    json_util.c
  * @brief   极简 JSON 取值实现
  ******************************************************************************
  */

#include "json_util.h"
#include <string.h>
#include <stdlib.h>
#include <stdio.h>

bool JSON_GetNestedInt(const char *json, const char *key, int *out)
{
  if (json == NULL || key == NULL) return false;
  char pat[64];
  int n = snprintf(pat, sizeof(pat), "\"%s\"", key);
  if (n < 0 || n >= (int)sizeof(pat)) return false;

  const char *p = strstr(json, pat);
  if (p == NULL) return false;
  const char *v = strstr(p, "\"value\"");
  if (v == NULL) return false;
  const char *c = strchr(v, ':');
  if (c == NULL) return false;
  c++;
  while (*c == ' ' || *c == '\t') c++;
  char *end;
  long val = strtol(c, &end, 10);
  if (end == c) return false;
  *out = (int)val;
  return true;
}

bool JSON_GetDirectInt(const char *json, const char *key, int *out)
{
  if (json == NULL || key == NULL) return false;
  char pat[64];
  int n = snprintf(pat, sizeof(pat), "\"%s\"", key);
  if (n < 0 || n >= (int)sizeof(pat)) return false;

  const char *p = strstr(json, pat);
  if (p == NULL) return false;
  const char *c = strchr(p, ':');
  if (c == NULL) return false;
  c++;
  while (*c == ' ' || *c == '\t') c++;
  char *end;
  long val = strtol(c, &end, 10);
  if (end == c) return false;
  *out = (int)val;
  return true;
}
