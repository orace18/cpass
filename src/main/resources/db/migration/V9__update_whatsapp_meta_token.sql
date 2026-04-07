-- Mettre à jour le token WHATSAPP_META
UPDATE providers
SET config_json    = '{"phoneNumberId":"167930809747321","accessToken":"EAAFjc4DPt6wBRGqqdZB7M4sm8uaNnMawTIUHDPkKqkFZCkiCAV3NTBIzqfSUPJgNeFs9SwiRkQRDzzKSKtv05iwu5qSf10Ld1NZALEgmRmwceF1UzZBRNGeLSvDXpQnvZCJ8u6bBMjPZA7IWaDyvZAzkr98k4yOjW78qx34o06mv2uBsB4Cc58ZCOM4s6m8nqWlwOAZDZD","templateLanguage":"fr","unitCost":0.05}',
    updated_at     = now()
WHERE code = 'WHATSAPP_META'
  AND channel = 'WHATSAPP';
