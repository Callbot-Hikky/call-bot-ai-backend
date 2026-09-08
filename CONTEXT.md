# Contexte : call-bot-ai-backend

Glossaire du langage métier. Aucun détail d'implémentation ici.

## Mode de garantie

Réglage **au niveau du restaurant**, valant pour toutes ses réservations. Trois valeurs
mutuellement exclusives :

- **Aucune** — la réservation est gratuite. Comportement historique.
- **Frais de réservation** — le client paie pour obtenir sa réservation.
- **Garantie no-show** — le client n'avance rien ; sa carte n'est débitée que s'il ne
  se présente pas.

Un restaurant est dans exactement un mode à un instant donné.

## Frais de réservation

Somme versée par le client pour obtenir une réservation.

**N'est pas déduite de l'addition** : le client règle son repas en entier par-dessus.
Ce n'est donc **ni un acompte** (qui serait déduit) **ni des arrhes** (qui autoriseraient
le client à se dédire en abandonnant la somme, et obligeraient le restaurateur qui annule
à en rembourser le double, art. 1590 C. civ.). C'est un droit d'entrée.

Remboursé intégralement si le client se désiste au-delà d'un délai fixé avant le service.
En deçà de ce délai, la somme reste acquise au restaurateur.

## Garantie no-show

Engagement de payer une pénalité en cas d'absence non annulée. Aucune somme n'est
prélevée à la réservation : seul un moyen de paiement est enregistré.

Le débit n'intervient que sur **constat explicite d'absence par le personnel** du
restaurant. Un client qui a dîné n'est jamais débité ; l'absence de constat vaut
présence.

## Fenêtre de remboursement

Délai, fixé par le restaurateur, précédant l'heure du service. Un client qui se désiste
**avant** ce délai récupère l'intégralité de ses frais de réservation ; passé ce délai,
la somme reste acquise au restaurateur. Il n'existe pas de remboursement partiel.

## Exemption de garantie

Décision du personnel de créer une réservation confirmée sans exiger la garantie
qu'impose le mode du restaurant. Elle est toujours explicite et laisse trace de son
auteur : c'est une dérogation, pas un mode de fonctionnement.

## Réservation pré-tenue

Réservation dont le créneau et la table sont bloqués, mais dont la garantie n'est pas
encore fournie. Elle occupe la table et empêche toute autre réservation dessus, sans
être une promesse ferme faite au client.

Elle ne survit pas à la **fenêtre de paiement** : passé ce délai, elle est annulée et
la table redevient disponible.

## Réservation confirmée

Réservation dont la garantie exigée par le mode du restaurant a été fournie — frais
réglés, ou moyen de paiement enregistré. C'est la seule forme de réservation qui
engage le restaurant vis-à-vis du client.

En mode « aucune », toute réservation est confirmée d'emblée.

## Fenêtre de paiement

Délai laissé au client, à compter de la prise de réservation, pour fournir la garantie.
La table lui est réservée pendant toute sa durée. À son terme, la réservation
pré-tenue disparaît et le client en est informé.

## No-show

Client qui ne se présente pas et n'a pas annulé. C'est un **constat humain**, posé par
le personnel, jamais déduit du silence du système.

---

*Termes en attente : la définition de « Réservation » en mode payant — savoir si elle
existe avant le paiement — reste à trancher.*
