package cn.qingli.competition

import org.junit.Assert.*
import org.junit.Test

class GradeMathTest {
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
