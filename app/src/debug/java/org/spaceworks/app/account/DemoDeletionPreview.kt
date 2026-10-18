package org.spaceworks.app.account

import androidx.compose.runtime.Composable

class DemoDeletionPreview(makerspaceIDs:List<Int>,private val makerName:(Int)->String?,private val canOpenStaff:(Int?)->Boolean) : AccountDeletionFlow {
    private val holder=DemoAccountDeletion(makerspaceIDs)
    override fun clear(){holder.clear()}
    @Composable override fun Screen(onBack:()->Unit,onFix:(Int?,AccountFixDestination)->Unit,onDeleted:()->Unit) {
        DemoAccountDeletionScreen(holder,onBack,{blocker,fix->
            onFix(blocker.makerspaceID,AccountFixDestination.valueOf(fix.name))
        },onDeleted,makerName,canOpenStaff)
    }
}
