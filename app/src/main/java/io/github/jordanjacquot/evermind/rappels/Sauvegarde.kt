package io.github.jordanjacquot.evermind.rappels

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor

/**
 * La sauvegarde du téléphone (compte Google) et le passage d'un téléphone à l'autre : Android copie l'agenda et les
 * réglages selon res/xml/backup_rules.xml et data_extraction_rules.xml (sauvegarde automatique, rien à faire ici).
 *
 * Après une restauration, Evermind n'a encore jamais été lancé sur ce téléphone : aucune alarme n'est posée. Android
 * prévient cet agent quand la restauration est finie ; il pose une alarme dans une minute, qui lance Evermind en
 * arrière-plan et règle les alarmes de tous les rappels, sans que l'utilisateur ait à ouvrir l'appli.
 * (Pendant la restauration, Android lance Evermind en mode réduit : rien d'autre que cette alarme.)
 */
class Sauvegarde : BackupAgent() {
    // (sauvegarde automatique seulement, android:fullBackupOnly : ces deux-là ne servent pas)
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) {}
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) {}

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        runCatching { Planificateur.revenirBientot(this) }
    }
}
