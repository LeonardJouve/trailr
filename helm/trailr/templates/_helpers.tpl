{{/* Expand the chart name. */}}
{{- define "trailr.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Create a release-qualified name. */}}
{{- define "trailr.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/* Create a component name while preserving its complete suffix. */}}
{{- define "trailr.componentFullname" -}}
{{- $maxLength := default 63 .maxLength | int -}}
{{- $suffix := printf "-%s" .component -}}
{{- $baseLength := sub $maxLength (len $suffix) | int -}}
{{- printf "%s%s" (include "trailr.fullname" .context | trunc $baseLength | trimSuffix "-") $suffix -}}
{{- end }}

{{/* Create the chart name and version label value. */}}
{{- define "trailr.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Common labels. */}}
{{- define "trailr.labels" -}}
helm.sh/chart: {{ include "trailr.chart" . | quote }}
{{ include "trailr.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end }}

{{/* Stable selector labels. */}}
{{- define "trailr.selectorLabels" -}}
app.kubernetes.io/name: {{ include "trailr.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end }}
