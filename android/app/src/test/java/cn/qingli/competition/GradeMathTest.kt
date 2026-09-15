package cn.qingli.competition

import org.junit.Assert.*
import org.junit.Test

class GradeMathTest {
    @Test fun qualitativeGradesUseOfficialGpaWithoutInventingNumericScore() {
        val s=summarizeGrades(listOf(Grade("2025-3","实践",2.0,null,4.0,"正常"),Grade("2025-3","高数",4.0,80.0,3.0,"正常")))
        assertEquals(80.0,s.average!!,0.001);assertEquals(20.0/6,s.gpa!!,0.001)
    }
    @Test fun semesterCodesUseAcademicOrder() {
        assertTrue(semesterOrder("2025-12")>semesterOrder("2025-3"))
        assertTrue(semesterOrder("2026-3")>semesterOrder("2025-16"))
        assertEquals("2025-2026 第二学期",semesterLabel("2025-12"))
    }
    @Test fun printRangesRejectInvalidAndReversedPages() {
        listOf("全部","1","1-3,5"," 2 - 9，12").forEach{assertTrue(it,validPrintRange(it))}
        listOf("","0","9-2","1,","-2","1.5","a","1-100000").forEach{assertFalse(it,validPrintRange(it))}
    }
    @Test fun weightsCoursesAndDoesNotAverageSemesterMeans() {
        val grades=listOf(Grade("1","高数",4.0,90.0,4.0,"正常"),Grade("2","英语",1.0,60.0,1.0,"正常"))
        val summary=summarizeGrades(grades)
        assertEquals(84.0,summary.average!!,0.001)
        assertEquals(3.4,summary.gpa!!,0.001)
    }
    @Test fun missingAndRetakeNeverBecomeZeroAndMissingGpaIsExplicit() {
        val summary=summarizeGrades(listOf(Grade("1","A",4.0,90.0,null,"正常"),Grade("1","B",1.0,null,null,"未发布"),Grade("1","C",2.0,70.0,2.0,"重修")))
        assertEquals(90.0,summary.average!!,0.001);assertNull(summary.gpa)
        assertEquals(2,summary.excluded);assertEquals(0.0,summary.gpaCredits,0.001)
    }
    @Test fun infeasibleTargetIsVisible() {
        assertEquals(250.0,targetAverage(60.0,90.0,10.0,79.0),0.001)
    }
    @Test fun quotedCommaAndBlankGradesParse() {
        val values=parseGradeCsv("学期,课程,学分,成绩,绩点,状态\n2026-1,\"设计,实践\",2,88,,正常\n2026-1,英语,3,,,未发布")
        assertEquals("设计,实践",values[0].course);assertNull(values[0].gpa);assertNull(values[1].score)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsDuplicateCourseBeforeSaving() {
        parseGradeCsv("学期,课程,学分,成绩,绩点,状态\n1,A,2,80,3,正常\n1,A,2,90,4,正常")
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsNonFiniteCredits() {
        parseGradeCsv("学期,课程,学分,成绩,绩点,状态\n1,A,NaN,80,3,正常")
    }
}
