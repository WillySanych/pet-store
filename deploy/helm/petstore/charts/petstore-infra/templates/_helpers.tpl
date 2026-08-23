{{- define "infra.labels" -}}
app.kubernetes.io/name: {{ . }}
app.kubernetes.io/part-of: petstore
{{- end -}}

{{- define "infra.selectorLabels" -}}
app.kubernetes.io/name: {{ . }}
{{- end -}}
