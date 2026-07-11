{{/*
Shared env for a game-server pod. Per-pod identity is injected from the downward API so the plugin
resolves it env-first; the DB password and forwarding secret come from Secrets. Call with:
  (dict "root" $ "family" .name "shutdownWhenEmpty" .shutdownWhenEmpty)
*/}}
{{- define "cryon.gameEnv" -}}
- name: EULA
  value: "{{ .root.Values.eula }}"
- name: POD_NAME
  valueFrom:
    fieldRef:
      fieldPath: metadata.name
- name: POD_IP
  valueFrom:
    fieldRef:
      fieldPath: status.podIP
- name: CRYON_SERVER_FAMILY
  value: "{{ .family }}"
- name: CRYON_INSTANCE_ID
  valueFrom:
    fieldRef:
      fieldPath: metadata.name
- name: CRYON_INSTANCE_ADDRESS
  valueFrom:
    fieldRef:
      fieldPath: status.podIP
- name: CRYON_INSTANCE_PORT
  value: "25565"
- name: CRYON_AGONES_SHUTDOWN_WHEN_EMPTY
  value: "{{ .shutdownWhenEmpty | default false }}"
- name: CRYON_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .root.Values.secrets.postgres }}
      key: password
- name: CRYON_FORWARDING_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ .root.Values.secrets.forwarding }}
      key: secret
{{- end -}}

{{/*
The GameServer template body shared by persistent and ephemeral Fleets. Call with:
  (dict "root" $ "family" .name "resources" .resources "shutdownWhenEmpty" .shutdownWhenEmpty)
*/}}
{{- define "cryon.gameServerTemplate" -}}
metadata:
  labels:
    cryon.dev/family: {{ .family }}
spec:
  ports:
    - name: minecraft
      containerPort: 25565
      protocol: TCP
      portPolicy: Dynamic
  health:
    initialDelaySeconds: 60
    periodSeconds: 5
    failureThreshold: 3
  template:
    metadata:
      labels:
        cryon.dev/family: {{ .family }}
    spec:
      containers:
        - name: server
          image: "{{ .root.Values.image.registry }}/cryon-{{ .family }}:{{ .root.Values.image.tag }}"
          imagePullPolicy: {{ .root.Values.image.pullPolicy }}
          resources:
{{ toYaml .resources | indent 12 }}
          env:
{{ include "cryon.gameEnv" (dict "root" .root "family" .family "shutdownWhenEmpty" .shutdownWhenEmpty) | indent 12 }}
          volumeMounts:
            - name: cryon-config
              mountPath: /cryon-config
              readOnly: true
      volumes:
        - name: cryon-config
          configMap:
            name: cryon-paper-config
{{- end -}}
