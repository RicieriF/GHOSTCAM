# GHOSTCAM Backend

Backend mínimo e funcional para licenças por dispositivo, checkout Square, dashboard administrativo, compatibilidade e releases.

## O que ele faz

- Uma licença por dispositivo.
- Planos fixos: DIÁRIO US$8/24h, 3 DIAS US$22/72h e SEMANAL US$50/7 dias.
- Validade inicia na primeira ativação usando o horário do servidor.
- Bloqueia a mesma licença em outro dispositivo até o administrador usar **Reset**.
- Renovação da mesma licença por +1, +3 ou +7 dias.
- Estados `ACTIVE`, `EXPIRED`, `SUSPENDED` e `REVOKED`.
- Checkout Square criado pelo backend e vinculado ao `deviceId`.
- Webhook Square cria a licença automaticamente quando o pagamento fica `COMPLETED`.
- Dashboard em `/admin` com dispositivo, licença, plano, tempo restante, status e ações.
- Relatórios voluntários de compatibilidade e consulta de alertas conhecidos.
- Registro de releases para update opcional/obrigatório.

## Implantação rápida (Cloudflare Worker + D1)

1. Instale Wrangler e autentique sua conta Cloudflare.
2. Crie um banco D1 chamado `ghostcam`.
3. Copie `wrangler.toml.example` para `wrangler.toml` e preencha o `database_id` e o `SQUARE_LOCATION_ID`.
4. Rode o schema: `wrangler d1 execute ghostcam --file=schema.sql --remote`.
5. Configure os segredos, sem colocar valores no Git:
   - `wrangler secret put ADMIN_TOKEN`
   - `wrangler secret put SQUARE_ACCESS_TOKEN`
   - `wrangler secret put SQUARE_WEBHOOK_SIGNATURE_KEY`
   - `wrangler secret put SQUARE_WEBHOOK_URL`
6. Publique com `wrangler deploy`.
7. No painel de desenvolvedor da Square, configure o webhook para `https://SEU-WORKER/api/square/webhook` e use exatamente essa URL como `SQUARE_WEBHOOK_URL`.
8. No app Android, altere `ghostcam_backend_base_url` para a URL pública do Worker e gere a APK final.

## Dashboard

Abra `https://SEU-WORKER/admin`. O token digitado na tela é mantido apenas no `localStorage` do navegador e é enviado no header `Authorization: Bearer ...` para as rotas administrativas.

## Square

As credenciais do Square ficam **somente no backend**. Nunca coloque `SQUARE_ACCESS_TOKEN` ou a chave do webhook dentro da APK. O app pede um checkout ao backend; o backend cria um Payment Link individual para aquele dispositivo e plano. Quando a Square confirma o pagamento via webhook, a licença é criada e fica disponível para o app consultar.

Use o ambiente `sandbox` primeiro. Depois do teste completo, mude `SQUARE_ENVIRONMENT` para `production` e use credenciais de produção.

## Privacidade

Os relatórios de compatibilidade devem ser enviados somente após ação/consentimento do usuário. O schema foi mantido deliberadamente mínimo: versão do app-alvo, versão do GHOSTCAM, Android/modelo e um resumo técnico. Não envie mídia, contatos, contas, credenciais ou conteúdo de outros apps.

## GPL

Este backend é um serviço separado. O aplicativo Android continua derivado do projeto GPL-3.0 e a distribuição da APK deve cumprir as obrigações correspondentes de disponibilização do código-fonte e avisos de licença.
