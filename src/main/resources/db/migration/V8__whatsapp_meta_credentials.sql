-- Désactiver ROUTE_MOBILE WhatsApp (non configuré, évite l'échec en priorité 1)
UPDATE providers
SET active = false,
    updated_at = now()
WHERE code = 'ROUTE_MOBILE'
  AND channel = 'WHATSAPP';

-- Configurer WHATSAPP_META avec les credentials et le mettre en priorité 1
UPDATE providers
SET config_json    = '{"phoneNumberId":"167930809747321","accessToken":"EAAFjc4DPt6wBQ4HwycJLfnEgKz4PghO29ToEZA77ZBwZAeO1zmAMg7rO6OzMVIWORmTUBNQp5ZCFrVChRbz6foUYSQxFHZA6A56xBwCDOxxboN8ZATphmNLK2MRThhAWLDpaQ2b3X3UZBSRF3RvRDcUGMEAw5X0R3RmUSsQcosEhvJB1GR3tTaujZAHnhQ8Yk2fesnAOH1Xnn4zEw5vEJF2cfH1aaDgT10d8t6qPLhYUG4kxTTaGy4n4i55LVBtPrKHavO7sjGu8pMvgGIedCY6QFHE2","templateLanguage":"fr","unitCost":0.05}',
    priority       = 1,
    active         = true,
    updated_at     = now()
WHERE code = 'WHATSAPP_META'
  AND channel = 'WHATSAPP';
