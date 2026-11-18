# Code Review

## 1. Change History

| **Change Date** | **Modified Sections** | **Rationale** |
|-----------------| --------------------- | ------------- |
| 2026-11-03      | Automated Code Review Results in response to Codacy information | Filling out the sections for submission to CP2 |

---

## 2. Automated Code Review Results

### 2.1. Commit Hash Where Codacy Ran

`4785596734333930d23872162073d954b9a2d4d2`

### 2.2. Unfixed Issues per Codacy Category

![2.2-1](images/2.2-codacy-1.png)
![2.2-2](images/2.2-codacy-2.png)

### 2.3. Unfixed Issues per Codacy Code Pattern

![2.3-1](images/2.3-codacy-1.png)
![2.3-2](images/2.3-codacy-2.png)

### 2.4. Justifications for Unfixed Issues

- **Code Pattern: Unsafe argument of type `any` assigned to a parameter of type `string`.**
  1. **Issue**
     - **Location in Git:** [`../backend/src/market/limitWorker.ts`](../backend/src/market/limitWorker.ts#L14)
     - **Justification:** issue regarding line of code that was already fixed
  2. **Issue**
     - **Location in Git:** [`../backend/src/routes/friends.ts`](../backend/src/routes/friends.ts#L62)
     - **Justification:** issue regarding line of code that was already fixed
- **Code Pattern: Unsafe return of an error typed value.**
  1. **Issue**
     - **Location in Git:** [`../backend/src/market/settlement.ts`](../backend/src/market/settlement.ts#L21)
     - **Justification:** issue regarding line of code that was already fixed
  2. **Issue**
     - **Location in Git:** [`../backend/src/market/settlement.ts`](../backend/src/market/settlement.ts#L38)
     - **Justification:** issue regarding line of code that was already fixed
- **Code Pattern: 'NodeJS' is not defined.**
  1. **Issue**
     - **Location in Git:** [`../backend/src/market/limitWorker.ts`](../backend/src/market/limitWorker.ts#L14)
     - **Justification:** issue regarding line of code that was already fixed
- **Code Pattern: Unexpected empty arrow function.**
  1. **Issue**
     - **Location in Git:** [`../backend/src/market/limitWorker.ts`](../backend/src/market/limitWorker.ts#L54)
     - **Justification:** issue regarding line of code that was already fixed
- ...

../backend/src/config/env.ts

---

## 3. Manual Code Review Results

### 3.1. Review You Performed

- **Peer group reviewed:** [WRITE_HERE]
- **Commit SHA reviewed:** `[Insert Commit SHA here]`
- **Date the artifacts were exchanged:** [WRITE_HERE]
- **Review report submitted:** [`<CP#>_<YourPeerGroupName>_Review.pdf`](#)

| **Section**          | **Item**                                                                                                                                             | **Score** |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | --------- |
| Manual code review   | Code is maintainable (well-documented, uses self-explanatory variable names, does not have complicated and long methods, magic numbers, etc.), efficient, handles error cases well | x/10      |
| Manual test review   | Tests are complete (all APIs exposed to the front-end are tested, three main features are tested), errors and edge cases are thoroughly tested, correct assertions are used | x/10      |
| Manual test review   | Test code is maintainable and well-structured                                                                                                          | x/10      |
| Manual test review   | Test implementation matches the requirements and design                                                                                                | x/10      |
| Manual test review   | Non-functional requirements are tested well                                                                                                            | x/10      |
| Manual test review   | Tests achieve high coverage and cases of < 100% coverage are well-justified                                                                            | x/10      |
| Manual test review   | All back-end tests can be run automatically                                                                                                            | x/10      |
| Automated code review| Codacy runs with the required setup                                                                                                                    | x/10      |
| Automated code review| All remaining Codacy issues are well-justified                                                                                                          | x/10      |

**Major fault reported**

- **Description:** ...
- **Steps to reproduce:** ...
- **Screenshots:** _(Placeholder for screenshots of the reported fault)_
- **Severity:** ...

### 3.2. Review You Received

- **Reviewing group:** [WRITE_HERE]
- **Commit SHA that was reviewed:** `[Insert Commit SHA here]`
- **Review report received:** [`<CP#>_<YourGroupName>_Review.pdf`](#)

| **Section**          | **Item**                                                                                                                                             | **Score** |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | --------- |
| Manual code review   | Code is maintainable, efficient, handles error cases well                                                                                               | x/10      |
| Manual test review   | Tests are complete, errors and edge cases are thoroughly tested, correct assertions are used                                                            | x/10      |
| Manual test review   | Test code is maintainable and well-structured                                                                                                          | x/10      |
| Manual test review   | Test implementation matches the requirements and design                                                                                                | x/10      |
| Manual test review   | Non-functional requirements are tested well                                                                                                            | x/10      |
| Manual test review   | Tests achieve high coverage and cases of < 100% coverage are well-justified                                                                            | x/10      |
| Manual test review   | All back-end tests can be run automatically                                                                                                            | x/10      |
| Automated code review| Codacy runs with the required setup                                                                                                                    | x/10      |
| Automated code review| All remaining Codacy issues are well-justified                                                                                                          | x/10      |

**Major fault reported against your project**

- **Description:** ...
- **Severity:** ...

### 3.3. Actions Taken in Response to the Review

| **Finding** | **Action Taken** | **Commit / Location in Git** |
| ----------- | ---------------- | ---------------------------- |
| ...         | ...              | [`src/...`](#)               |
| ...         | ...              | ...                          |

- **Findings not acted on.** For each finding you decided not to address, give the reason:
  1. **Finding:** ...
     - **Justification:** ...
