package ai.autolog

import android.content.Context

/**
 * 운행기록부 머리말과 사용자 칸에 들어가는 정보. 국세청 서식의 상호·사업자등록번호·차종·자동차등록번호와 사용자(부서·성명).
 * 사용자는 운행이 기록될 때의 값을 운행마다 남긴다. 나중에 바꿔도 이미 기록된 운행은 그대로 둔다.
 */
data class Profile(
    val company: String = "",
    val bizNo: String = "",
    val carModel: String = "",
    val plate: String = "",
    val dept: String = "",
    val name: String = "",
) {
    /** 화면 요약용 한 줄. 아무것도 없으면 빈 문자열. */
    fun summary() = listOf(plate, carModel, listOf(dept, name).filter { it.isNotEmpty() }.joinToString(" "))
        .filter { it.isNotEmpty() }.joinToString(" · ")
}

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    fun load() = Profile(
        company = prefs.getString("company", "")!!,
        bizNo = prefs.getString("bizNo", "")!!,
        carModel = prefs.getString("carModel", "")!!,
        plate = prefs.getString("plate", "")!!,
        dept = prefs.getString("dept", "")!!,
        name = prefs.getString("name", "")!!,
    )

    fun save(p: Profile) = prefs.edit()
        .putString("company", p.company.trim()).putString("bizNo", p.bizNo.trim())
        .putString("carModel", p.carModel.trim()).putString("plate", p.plate.trim())
        .putString("dept", p.dept.trim()).putString("name", p.name.trim())
        .apply()
}
