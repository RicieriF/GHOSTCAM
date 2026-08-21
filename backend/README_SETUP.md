# GHOSTCAM backend — conexão final

O código do backend e da dashboard já está preparado. Para colocar em produção faltam apenas as credenciais/contas do proprietário.

## 1. Cloudflare

1. Crie um D1 Database chamado `ghostcam`.
2. Aplique `schema.sql` no banco.
3. Copie `wrangler.toml.example` para `wrangler.toml` e coloque o `database_id` do D1 e a URL pública do Worker.
4. Configure um token forte de administrador:
   - `wrangler secret put ADMIN_TOKEN`
5. Faça o deploy do Worker.

A dashboard ficará em:

`https://SEU-WORKER/admin`

Ela mostra dispositivo, licença, plano, dias restantes, status, último check e controles de reset/renovação/suspensão.

## 2. Square

Na sua conta Square, obtenha:

- Access Token
- Location ID
- Webhook Signature Key

Configure no Worker:

- `wrangler secret put SQUARE_ACCESS_TOKEN`
- `wrangler secret put SQUARE_WEBHOOK_SIGNATURE_KEY`
- `SQUARE_LOCATION_ID` no `wrangler.toml`

Cadastre no Square o webhook:

`https://SEU-WORKER/api/square/webhook`

Evento usado: `payment.updated`.

Os planos já estão definidos no backend:

- Diário: US$ 8 / 24 horas
- 3 dias: US$ 22 / 72 horas
- Semanal: US$ 50 / 7 dias

## 3. Aplicativo

Depois do Worker estar publicado, coloque a URL HTTPS dele em:

`app/src/main/java/io/github/zensu357/camswap/GhostCamCommercialConfig.kt`

Campo:

`BASE_URL`

Não coloque Access Token do Square nem ADMIN_TOKEN dentro do APK.

## 4. Teste final

Fluxo esperado:

1. Cliente abre GHOSTCAM e vê o ID `GH-XXXX-XXXX`.
2. Escolhe um plano e toca em COMPRAR.
3. Square abre o checkout hospedado.
4. Pagamento concluído dispara webhook.
5. Backend cria uma licença `GHOST-XXXX-XXXX-XXXX` vinculada ao dispositivo.
6. A página de retorno mostra a chave.
7. Cliente ativa a chave no GHOSTCAM.
8. Dashboard exibe dispositivo, licença, plano, dias restantes e status.

Para trocar o aparelho, use **Reset** na dashboard e ative a mesma licença no novo dispositivo.
