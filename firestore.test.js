const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write user workspace or assignments", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const past = new Date(Date.now() - 2000);
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("assignments").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      moodleUrl: "https://moodle.1skola.lv",
      connectionMode: "DISCONNECTED",
      moodleDisplayName: "",
      preferredModel: "gemini-3.8-flash",
      answerLanguage: "Русский",
      courseCount: 0,
      createdAt: past,
      updatedAt: past,
    })
  );
});

test("Owner can create and update UserWorkspace, Courses, Assignments, AgentLogs, and ChatMessages", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);
  const updated = new Date(Date.now() - 1000);

  // UserWorkspace
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      moodleUrl: "https://moodle.1skola.lv",
      connectionMode: "BROWSER_SESSION",
      moodleDisplayName: "Student Name",
      preferredModel: "gemini-3.8-flash",
      answerLanguage: "Русский",
      courseCount: 1,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      preferredModel: "gemini-4-argon",
      answerLanguage: "English",
      updatedAt: updated,
    })
  );

  // SyncedCourse
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("courses")
      .doc("course_42")
      .set({
        userId: ALICE_UID,
        moodleCourseId: "42",
        title: "Matematika 11",
        shortName: "MAT11",
        category: "Exact Sciences",
        courseUrl: "https://moodle.1skola.lv/course/view.php?id=42",
        progressPercent: 60,
        assignmentCount: 2,
        createdAt: created,
        updatedAt: created,
      })
  );

  // AssignmentRecord create & action-based updates
  const assignRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("assignments")
    .doc("assign_101");

  await assertSucceeds(
    assignRef.set({
      userId: ALICE_UID,
      moodleAssignId: "101",
      moodleCourseId: "42",
      courseTitle: "Matematika 11",
      title: "Quadratic Equations",
      description: "Solve x^2 - 5x + 6 = 0",
      dueDateText: "2026-11-20 23:59",
      dueEpochMillis: 1795219140000,
      status: "PENDING",
      subjectCategory: "Математика",
      assignmentUrl: "https://moodle.1skola.lv/mod/assign/view.php?id=101",
      attachmentsSummary: "",
      aiSolution: "",
      aiExplanation: "",
      userEditedAnswer: "",
      recommendedFormat: "",
      verificationNotes: "",
      submittedByUser: false,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Save AI solution
  await assertSucceeds(
    assignRef.update({
      subjectCategory: "Математика",
      aiSolution: "x1 = 2, x2 = 3",
      aiExplanation: "Factor into (x-2)(x-3)=0",
      userEditedAnswer: "x1 = 2, x2 = 3",
      recommendedFormat: "Text",
      verificationNotes: "Checked by substitution",
      status: "DRAFT_READY",
      updatedAt: updated,
    })
  );

  // Confirm & Submit
  await assertSucceeds(
    assignRef.update({
      userEditedAnswer: "x1 = 2, x2 = 3 (confirmed)",
      status: "SUBMITTED",
      submittedByUser: true,
      updatedAt: updated,
    })
  );

  // Terminal state lock: cannot edit SUBMITTED assignment answer
  await assertFails(
    assignRef.update({
      userEditedAnswer: "Tampered after submission",
      status: "DRAFT_READY",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // AgentLogRecord create & update
  const logRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("agentLogs")
    .doc("log_1");
  await assertSucceeds(
    logRef.set({
      userId: ALICE_UID,
      actionType: "SOLVE_ASSIGNMENT",
      assignmentId: "assign_101",
      assignmentTitle: "Quadratic Equations",
      courseTitle: "Matematika 11",
      summary: "Running agent pipeline",
      status: "RUNNING",
      steps: ["1. Connected to Moodle", "2. Fetched assignment"],
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertSucceeds(
    logRef.update({
      summary: "Solution ready, awaiting user confirmation",
      status: "AWAITING_CONFIRMATION",
      steps: ["1. Connected", "2. Fetched", "3. Solved", "4. Awaiting confirmation"],
      updatedAt: updated,
    })
  );

  // ChatMessageRecord create & list
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("chatMessages")
      .doc("msg_1")
      .set({
        userId: ALICE_UID,
        role: "user",
        text: "Найди все мои невыполненные задания",
        relatedCourseTitle: "",
        relatedAssignmentTitle: "",
        createdAt: created,
        updatedAt: created,
      })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("assignments").get()
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("agentLogs").get()
  );
});

test("Cross-user isolation and schema hardening reject invalid payloads", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    bobDb.collection("users").doc(BOB_UID).collection("courses").doc("c_1").set({
      userId: BOB_UID,
      moodleCourseId: "1",
      title: "Latviesu valoda",
      shortName: "LV",
      category: "Languages",
      courseUrl: "https://moodle.1skola.lv/course/view.php?id=1",
      progressPercent: 10,
      assignmentCount: 1,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Alice cannot read Bob's courses or assignments
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("courses").get()
  );
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("courses").doc("c_1").get()
  );

  // Reject ghost field / @DocumentId "id" field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("courses").doc("c_2").set({
      id: "c_2",
      userId: ALICE_UID,
      moodleCourseId: "2",
      title: "English",
      shortName: "EN",
      category: "Languages",
      courseUrl: "https://moodle.1skola.lv/course/view.php?id=2",
      progressPercent: 0,
      assignmentCount: 0,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject future timestamp
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("courses").doc("c_3").set({
      userId: ALICE_UID,
      moodleCourseId: "3",
      title: "History",
      shortName: "HIS",
      category: "Humanities",
      courseUrl: "https://moodle.1skola.lv/course/view.php?id=3",
      progressPercent: 0,
      assignmentCount: 0,
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );
});
