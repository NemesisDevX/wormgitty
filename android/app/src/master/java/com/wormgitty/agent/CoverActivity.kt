package com.wormgitty.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme

/** master cover: same shape as ghost, programming-education topics. */
class CoverActivity : ComponentActivity() {

    private val topics = listOf(
        CoverTopic("p1", "أساسيات Kotlin", "المتغيرات والدوال · ١٤ دقيقة",
            "Kotlin هي اللغة الرسمية لتطوير تطبيقات أندرويد. في هذا الدرس نتعرف على إعلان المتغيرات باستخدام val وvar، وأنواع البيانات الأساسية، وكتابة الدوال بصيغة مختصرة.\n\nسنتعلم أيضًا عن null safety — الميزة التي تميز Kotlin عن Java — وكيفية استخدام العاملين ? و ?: للتعامل مع القيم الفارغة بأمان."),
        CoverTopic("p2", "واجهات Jetpack Compose", "التصميم التعريفي · ١٨ دقيقة",
            "Jetpack Compose هو إطار العمل الحديث لبناء واجهات أندرويد بطريقة تعريفية. بدلًا من ملفات XML نكتب الواجهة كدوال قابلة للتركيب (Composable Functions) تتفاعل مع الحالة تلقائيًا.\n\nفي هذا الدرس نبني أول شاشة: Column وRow وText وButton، ونستخدم remember وmutableStateOf لإدارة الحالة داخل الواجهة."),
        CoverTopic("p3", "أساسيات Git", "التحكم في الإصدارات · ١٢ دقيقة",
            "Git هو نظام التحكم في الإصدارات الأكثر استخدامًا في العالم. نتعلم في هذا الدرس المفاهيم الأساسية: المستودع (repository)، واللقطة (commit)، والفرع (branch)، والدمج (merge).\n\nنتدرب على أوامر git init وadd وcommit وbranch، وكيفية رفع العمل إلى مستودع بعيد وحل التعارضات البسيطة."),
        CoverTopic("p4", "البنى التحتية للبيانات", "المصفوفات والقوائم · ١٦ دقيقة",
            "هياكل البيانات هي أساس كتابة البرامج الكفوءة. نبدأ بالمصفوفة (Array) وأوقات الوصول الثابتة O(1)، ثم القائمة المتصلة (Linked List) وميزاتها في الإدراج والحذف.\n\nنقارن أيضًا بين المكدس (Stack) بترتيب LIFO والطابور (Queue) بترتيب FIFO، ونرى أمثلة عملية على استخدام كل منهما."),
        CoverTopic("p5", "أساسيات الشبكات و HTTP", "الطلبات والاستجابات · ١٥ دقيقة",
            "كل تطبيق حديث يتواصل مع خوادم عبر HTTP. في هذا الدرس نفهم دورة الطلب والاستجابة: methods مثل GET وPOST، ورموز الحالة مثل 200 و404، وتنسيق البيانات JSON.\n\nنتعلم أيضًا أساسيات REST، وكيفية إرسال طلبات من تطبيق أندرويد باستخدام مكتبات الشبكة الشائعة ومعالجة الاستجابات بأمان."),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { CourseCoverScreen(this, getString(R.string.app_name), topics) } }
    }
}
