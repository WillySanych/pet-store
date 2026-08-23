{{- define "petstore.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "petstore.labels" -}}
app.kubernetes.io/name: {{ include "petstore.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: petstore
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "petstore.selectorLabels" -}}
app.kubernetes.io/name: {{ include "petstore.name" . }}
{{- end -}}
