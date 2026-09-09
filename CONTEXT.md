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

## Compte de paiement

Compte, ouvert auprès du prestataire de paiement, sur lequel arrivent les frais réglés
par les clients d'un restaurant.

Il appartient **au restaurant**, un par établissement. Un tel compte est lié à une entité
légale et à un compte bancaire : deux restaurants d'un même propriétaire sont souvent deux
sociétés, et un compte partagé verserait l'argent de l'un sur la banque de l'autre.

L'argent des clients n'est jamais celui d'Alloquence : il est versé au restaurateur dès
l'encaissement, et Alloquence ne fait que retenir sa commission au passage.

Tant que ce compte n'est **pas validé** par le prestataire, aucun mode payant n'est
activable : le client tomberait sur une page de paiement en échec après s'être vu
annoncer une table retenue.

## Commission

Part qu'Alloquence conserve sur les **frais de réservation** : un pourcentage du montant
plus une somme fixe.

Ne s'applique **jamais aux pénalités no-show** : celles-ci dédommagent un restaurateur
d'une table perdue, et en prélever une part serait facturer le malheur d'autrui.

## Reversement

Envoi vers la banque du restaurateur des frais encaissés, net de la commission.

N'a lieu qu'**un jour après le service**, jamais avant : un client qui annule dans sa
fenêtre de remboursement doit pouvoir être remboursé sur une somme qui n'est pas partie.

## Lien du client

Adresse à usage personnel envoyée au client par message, seul moyen dont il dispose
pour agir sur sa réservation : il a réservé par téléphone et n'a pas de compte.

Il en existe deux, distincts :

- le **lien de paiement**, valable le temps de la fenêtre de paiement et à usage unique ;
- le **lien d'annulation**, qui survit au règlement — c'est justement après avoir payé
  qu'on peut avoir besoin d'annuler.

## Constat d'absence

Acte par lequel un membre du personnel déclare qu'un client n'est pas venu.

Toujours **humain, et attribué**. Le système ne déduit jamais une absence de son propre
silence : une table que personne n'a marquée est une table qui a été honorée.

Le constat ne débite rien. Il ouvre une **fenêtre d'annulation** de deux heures pendant
laquelle il peut être repris, ce qui rend inoffensif un constat posé par erreur. Ce n'est
qu'une fois cette fenêtre fermée que la pénalité devient exigible.

## Carte enregistrée

Moyen de paiement qu'un client confie en garantie no-show, sans qu'aucune somme ne soit
prélevée au moment de la réservation.

Elle est conservée **sur le compte de paiement du restaurateur**, jamais chez Alloquence :
c'est lui qui débitera si la table est perdue, et un moyen de paiement enregistré sur un
compte ne peut pas être utilisé depuis un autre.

Elle est oubliée une fois le service passé et la pénalité réglée ou abandonnée.

## No-show

Client qui ne se présente pas et n'a pas annulé. C'est un **constat humain**, posé par
le personnel, jamais déduit du silence du système.

---

*Termes en attente : la définition de « Réservation » en mode payant — savoir si elle
existe avant le paiement — reste à trancher.*
